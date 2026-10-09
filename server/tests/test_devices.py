import sqlite3
from dataclasses import replace
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient

from callagent.api import create_app
from callagent.config import load_settings
from callagent.db import make_session_factory
from callagent.models import Device, utcnow

KEY = {"X-API-Key": "test-key"}


@pytest.fixture
def settings(tmp_path):
    return replace(load_settings(), admin_key="test-key", data_dir=tmp_path,
                   public_url="http://192.168.1.10:8100")


@pytest.fixture
def client(settings):
    return TestClient(create_app(settings))


def create(client, name="Ali"):
    r = client.post("/api/admin/devices", json={"name": name}, headers=KEY)
    assert r.status_code == 200, r.text
    return r.json()


def activate(client, code, **info):
    return client.post("/api/devices/activate", json={"code": code, **info})


def bearer(token):
    return {"Authorization": f"Bearer {token}"}


CALL = {"id": "c1", "direction": "outgoing", "phone_number": "0912",
        "started_at": "2026-10-09T10:00:00+03:30"}


def test_admin_endpoints_need_admin_key(client):
    assert client.get("/api/admin/devices").status_code == 401
    assert client.post("/api/admin/devices", json={"name": "x"}).status_code == 401


def test_create_device_gives_pending_code_and_qr(client):
    d = create(client)
    assert d["status"] == "pending"
    assert len(d["activation_code"]) == 9 and d["activation_code"][4] == "-"
    qr = client.get(f"/api/admin/devices/{d['id']}/qr.svg", headers=KEY)
    assert qr.status_code == 200 and qr.text.startswith("<svg")


def test_server_address_for_qr_uses_public_url(client):
    info = client.get("/api/admin/server-info", headers=KEY).json()
    assert info == {"server": "http://192.168.1.10:8100", "public_url_configured": True,
                    "other_addresses": []}


def test_lan_address_preferred_over_vpn_and_wsl(monkeypatch):
    from callagent import devices
    monkeypatch.setattr(devices.socket, "getaddrinfo", lambda *a, **k: [
        (0, 0, 0, "", (ip, 0)) for ip in ["10.235.43.209", "172.31.96.1", "192.168.1.4", "127.0.0.1"]
    ])
    assert devices.lan_ips()[0] == "192.168.1.4"
    assert "127.0.0.1" not in devices.lan_ips()


def test_activation_flow(client):
    d = create(client)
    r = activate(client, d["activation_code"].lower(), brand="Xiaomi", model="Redmi Note 13",
                 android_version="14", app_version="0.1.0")
    assert r.status_code == 200
    token = r.json()["token"]
    assert r.json()["name"] == "Ali"

    # code is single-use
    assert activate(client, d["activation_code"]).status_code == 400

    [listed] = client.get("/api/admin/devices", headers=KEY).json()
    assert listed["status"] == "active"
    assert listed["activation_code"] is None
    assert (listed["brand"], listed["model"]) == ("Xiaomi", "Redmi Note 13")

    up = client.post("/api/calls", data=CALL, headers=bearer(token))
    assert up.status_code == 200 and up.json()["device_id"] == d["id"]


def test_expired_code_is_rejected(client, settings):
    d = create(client)
    Session = make_session_factory(settings)
    with Session() as s:
        s.get(Device, d["id"]).activation_expires_at = utcnow() - timedelta(minutes=1)
        s.commit()
    assert activate(client, d["activation_code"]).status_code == 400


def test_revoke_blocks_uploads(client):
    d = create(client)
    token = activate(client, d["activation_code"]).json()["token"]
    client.post(f"/api/admin/devices/{d['id']}/revoke", headers=KEY)
    assert client.post("/api/calls", data=CALL, headers=bearer(token)).status_code == 401
    assert client.post(f"/api/admin/devices/{d['id']}/new-code", headers=KEY).status_code == 409


def test_new_code_moves_device_to_new_phone(client):
    d = create(client)
    old = activate(client, d["activation_code"]).json()["token"]
    code2 = client.post(f"/api/admin/devices/{d['id']}/new-code", headers=KEY).json()["activation_code"]
    # old phone still works until the new one activates
    assert client.post("/api/calls", data=CALL, headers=bearer(old)).status_code == 200
    new = activate(client, code2).json()["token"]
    assert client.post("/api/calls", data={**CALL, "id": "c2"}, headers=bearer(old)).status_code == 401
    assert client.post("/api/calls", data={**CALL, "id": "c2"}, headers=bearer(new)).status_code == 200


def test_call_id_cannot_be_hijacked_by_other_device(client):
    t1 = activate(client, create(client, "A")["activation_code"]).json()["token"]
    t2 = activate(client, create(client, "B")["activation_code"]).json()["token"]
    assert client.post("/api/calls", data=CALL, headers=bearer(t1)).status_code == 200
    assert client.post("/api/calls", data=CALL, headers=bearer(t2)).status_code == 409


def test_admin_key_generated_when_not_configured(tmp_path):
    s = replace(load_settings(), admin_key="", data_dir=tmp_path)
    c = TestClient(create_app(s))
    key = (tmp_path / "admin.key").read_text().strip()
    assert len(key) > 20
    assert c.get("/api/admin/devices", headers={"X-API-Key": key}).status_code == 200
    # same key on restart
    TestClient(create_app(s))
    assert (tmp_path / "admin.key").read_text().strip() == key


def test_old_database_gets_new_columns(tmp_path):
    # database from v0.1, before devices existed
    con = sqlite3.connect(tmp_path / "callagent.db")
    con.execute("CREATE TABLE calls (id VARCHAR(64) PRIMARY KEY, direction VARCHAR(16), "
                "phone_number VARCHAR(32), started_at DATETIME, duration_sec INTEGER, "
                "audio_path VARCHAR(512), transcript TEXT, status VARCHAR(16), error TEXT, "
                "created_at DATETIME, updated_at DATETIME)")
    con.commit()
    con.close()
    make_session_factory(replace(load_settings(), data_dir=tmp_path))
    con = sqlite3.connect(tmp_path / "callagent.db")
    cols = {r[1] for r in con.execute("PRAGMA table_info(calls)")}
    con.close()
    assert "device_id" in cols
