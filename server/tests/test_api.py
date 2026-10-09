from dataclasses import replace

import pytest
from fastapi.testclient import TestClient

from callagent.api import create_app
from callagent.config import load_settings
from callagent.db import make_session_factory
from callagent.worker import process_one

KEY = {"X-API-Key": "test-key"}


@pytest.fixture
def settings(tmp_path):
    return replace(load_settings(), admin_key="test-key", data_dir=tmp_path, public_url="")


def activate_device(client, name="Ali"):
    d = client.post("/api/admin/devices", json={"name": name}, headers=KEY).json()
    r = client.post("/api/devices/activate", json={"code": d["activation_code"], "brand": "samsung"})
    assert r.status_code == 200, r.text
    return {"Authorization": f"Bearer {r.json()['token']}"}


@pytest.fixture
def client(settings):
    c = TestClient(create_app(settings))
    c.dev_headers = activate_device(c)
    return c


def form(**over):
    data = {
        "id": "dev1-1001",
        "direction": "incoming",
        "phone_number": "09121234567",
        "started_at": "2026-10-09T10:30:00+03:30",
        "duration_sec": "95",
    }
    data.update(over)
    return data


def upload(client, audio=True, **over):
    files = {"audio": ("rec.m4a", b"fake-audio", "audio/mp4")} if audio else None
    return client.post("/api/calls", data=form(**over), files=files, headers=client.dev_headers)


def test_upload_requires_device_token(client):
    assert client.post("/api/calls", data=form()).status_code == 401
    assert client.post("/api/calls", data=form(), headers=KEY).status_code == 401
    bad = {"Authorization": "Bearer nope"}
    assert client.post("/api/calls", data=form(), headers=bad).status_code == 401


def test_reading_calls_requires_admin_key(client):
    upload(client)
    assert client.get("/api/calls").status_code == 401
    assert client.get("/api/calls", headers=client.dev_headers).status_code == 401


def test_upload_with_audio_is_queued(client, settings):
    r = upload(client)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["status"] == "queued"
    assert body["has_audio"] is True
    assert body["started_at"].startswith("2026-10-09T07:00:00")  # stored in UTC
    assert (settings.audio_dir / "2026" / "10" / "dev1-1001.m4a").read_bytes() == b"fake-audio"


def test_upload_without_audio(client):
    r = upload(client, audio=False)
    assert r.json()["status"] == "no_audio"


def test_reupload_is_idempotent_and_can_attach_audio(client):
    upload(client, audio=False)
    assert upload(client, audio=False).json()["status"] == "no_audio"
    assert upload(client).json()["status"] == "queued"
    assert len(client.get("/api/calls", headers=KEY).json()) == 1


@pytest.mark.parametrize(
    "over",
    [{"direction": "missed"}, {"id": "../etc"}, {"started_at": "2026-10-09T10:30:00"}],
)
def test_rejects_bad_input(client, over):
    assert upload(client, **over).status_code == 422


def test_rejects_unknown_audio_type(client):
    r = client.post(
        "/api/calls", data=form(), files={"audio": ("x.exe", b"x")}, headers=client.dev_headers
    )
    assert r.status_code == 422


def test_filter_by_phone_number(client):
    upload(client, id="a", phone_number="0912")
    upload(client, id="b", phone_number="0935")
    ids = [c["id"] for c in client.get("/api/calls?phone_number=0935", headers=KEY).json()]
    assert ids == ["b"]


def seg(start, end, text, speaker=None):
    return {"start": start, "end": end, "text": text, "speaker": speaker}


class FakeTranscriber:
    def __init__(self, fail=False, segments=None):
        self.fail = fail
        self.segments = segments or [seg(0.0, 1.5, "سلام، وقت بخیر", "agent")]

    def transcribe(self, path):
        assert path.exists()
        if self.fail:
            raise RuntimeError("boom")
        return self.segments


def test_worker_transcribes_queued_call(client, settings):
    upload(client)
    Session = make_session_factory(settings)
    assert process_one(Session, settings, FakeTranscriber()) is True
    assert process_one(Session, settings, FakeTranscriber()) is False  # queue empty
    call = client.get("/api/calls/dev1-1001", headers=KEY).json()
    assert call["status"] == "done"
    assert call["transcript"] == "سلام، وقت بخیر"


def test_worker_records_failure(client, settings):
    upload(client)
    Session = make_session_factory(settings)
    process_one(Session, settings, FakeTranscriber(fail=True))
    call = client.get("/api/calls/dev1-1001", headers=KEY).json()
    assert call["status"] == "failed"
    assert "boom" in call["error"]


def test_numbers_in_transcript_are_returned_with_spans(client, settings):
    upload(client)
    fake = FakeTranscriber(segments=[
        seg(0, 2, "سلام", "agent"),
        seg(2, 9, "شماره‌م صفر نهصد و دوازده چهارصد و پنجاه و یک بیست و پنج نود و هفت هست", "customer"),
    ])
    process_one(make_session_factory(settings), settings, fake)
    call = client.get("/api/calls/dev1-1001", headers=KEY).json()
    [n] = call["numbers"]
    assert n["type"] == "phone" and n["value"] == "09124512597" and n["exact"] is True
    assert call["transcript"][n["start"]:n["end"]].startswith("صفر نهصد")
    # the same number, located inside its own segment
    second = call["segments"][1]
    assert second["speaker"] == "customer"
    [m] = second["numbers"]
    assert second["text"][m["start"]:m["end"]].startswith("صفر نهصد")


def test_demo_page_is_served(client):
    assert client.get("/static/transcript-view.js").status_code == 200


def test_audio_download_for_admin(client):
    upload(client)
    assert client.get("/api/calls/dev1-1001/audio").status_code == 401
    r = client.get("/api/calls/dev1-1001/audio", headers=KEY)
    assert r.status_code == 200
    assert r.content == b"fake-audio"
    assert r.headers["content-type"] == "audio/mp4"


def test_no_audio_returns_404(client):
    upload(client, audio=False)
    assert client.get("/api/calls/dev1-1001/audio", headers=KEY).status_code == 404


@pytest.mark.parametrize("raw", ["+989121234567", "00989121234567", "0912 123 4567", "09121234567"])
def test_phone_numbers_are_normalized(client, raw):
    assert upload(client, phone_number=raw).json()["phone_number"] == "09121234567"
    found = client.get("/api/calls", params={"phone_number": "+989121234567"}, headers=KEY).json()
    assert [c["id"] for c in found] == ["dev1-1001"]


def transcribed(client, settings, segments):
    upload(client)
    process_one(make_session_factory(settings), settings, FakeTranscriber(segments=segments))


def test_segments_keep_times_and_speakers(client, settings):
    transcribed(client, settings, [seg(0.5, 3.0, "سلام", "agent"), seg(3.2, 5.0, "سلام بفرمایید", "customer")])
    call = client.get("/api/calls/dev1-1001", headers=KEY).json()
    assert [(x["start"], x["speaker"], x["text"]) for x in call["segments"]] == [
        (0.5, "agent", "سلام"), (3.2, "customer", "سلام بفرمایید")]
    assert call["transcript"] == "سلام\nسلام بفرمایید"
    assert call["final_text"] is None


def test_save_and_clear_final_text(client, settings):
    transcribed(client, settings, [seg(0, 1, "الف", "agent"), seg(1, 2, "ب"), seg(2, 3, "ج", "customer")])
    body = {"text": "- الف\n+ ج (اصلاح‌شده)", "selection": [2, 0, 2]}
    assert client.put("/api/calls/dev1-1001/final", json=body).status_code == 401
    r = client.put("/api/calls/dev1-1001/final", json=body, headers=KEY).json()
    assert r["final_text"] == "- الف\n+ ج (اصلاح‌شده)"
    assert r["final_selection"] == [0, 2]
    assert r["final_updated_at"] is not None
    # the original transcript is never touched
    assert r["transcript"] == "الف\nب\nج"
    cleared = client.put("/api/calls/dev1-1001/final", json={"text": "  "}, headers=KEY).json()
    assert cleared["final_text"] is None and cleared["final_selection"] == []


def test_retranscribe_requeues(client, settings):
    transcribed(client, settings, [seg(0, 1, "x")])
    r = client.post("/api/calls/dev1-1001/retranscribe", headers=KEY).json()
    assert r["status"] == "queued"
