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
    return replace(load_settings(), api_key="test-key", data_dir=tmp_path)


@pytest.fixture
def client(settings):
    return TestClient(create_app(settings))


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
    return client.post("/api/calls", data=form(**over), files=files, headers=KEY)


def test_requires_api_key(client):
    r = client.post("/api/calls", data=form())
    assert r.status_code == 401


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
        "/api/calls", data=form(), files={"audio": ("x.exe", b"x")}, headers=KEY
    )
    assert r.status_code == 422


def test_filter_by_phone_number(client):
    upload(client, id="a", phone_number="0912")
    upload(client, id="b", phone_number="0935")
    ids = [c["id"] for c in client.get("/api/calls?phone_number=0935", headers=KEY).json()]
    assert ids == ["b"]


class FakeTranscriber:
    def __init__(self, fail=False):
        self.fail = fail

    def transcribe(self, path):
        assert path.exists()
        if self.fail:
            raise RuntimeError("boom")
        return "سلام، وقت بخیر"


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
    fake = FakeTranscriber()
    fake.transcribe = lambda path: "شماره‌م صفر نهصد و دوازده چهارصد و پنجاه و یک بیست و پنج نود و هفت هست"
    process_one(make_session_factory(settings), settings, fake)
    call = client.get("/api/calls/dev1-1001", headers=KEY).json()
    [n] = call["numbers"]
    assert n["type"] == "phone" and n["value"] == "09124512597" and n["exact"] is True
    assert call["transcript"][n["start"]:n["end"]].startswith("صفر نهصد")


def test_demo_page_is_served(client):
    assert client.get("/static/transcript-view.js").status_code == 200
