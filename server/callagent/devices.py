"""Phone registration: the admin creates a device, the phone scans its QR code and activates.

QR content (JSON):  {"v": 1, "server": "http://192.168.1.10:8100", "code": "ABCD-EFGH"}
The phone posts the code to /api/devices/activate and receives its own secret token,
which it then sends as "Authorization: Bearer <token>" when uploading calls.
"""
import hashlib
import json
import secrets
import socket
import uuid
from datetime import datetime, timedelta, timezone

import segno
from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel, Field
from sqlalchemy import select

from .models import Device, DeviceStatus, utcnow

ACTIVATION_TTL = timedelta(hours=24)
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O, 1/I: easy to type by hand


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def new_activation_code() -> str:
    raw = "".join(secrets.choice(CODE_ALPHABET) for _ in range(8))
    return f"{raw[:4]}-{raw[4:]}"


def _ip_rank(ip: str) -> int:
    # Office Wi-Fi/LAN is almost always 192.168.x.x; 10.x is often a VPN and
    # 172.16-31.x is often Docker/WSL, so those come later.
    if ip.startswith("192.168."):
        return 0
    if ip.startswith("10."):
        return 1
    if ip.startswith("172."):
        return 2
    return 3


def lan_ips() -> list[str]:
    """This machine's IPv4 addresses, most likely LAN address first."""
    ips: set[str] = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("10.255.255.255", 1))  # no packet is sent; picks the default route
            ips.add(s.getsockname()[0])
        except OSError:
            pass
    ips = {ip for ip in ips if not ip.startswith(("127.", "169.254."))}
    return sorted(ips, key=lambda ip: (_ip_rank(ip), ip)) or ["127.0.0.1"]


def as_utc(dt: datetime | None) -> datetime | None:
    if dt is not None and dt.tzinfo is None:  # SQLite drops the offset; values are UTC
        return dt.replace(tzinfo=timezone.utc)
    return dt


class DeviceCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)


class DeviceOut(BaseModel):
    id: str
    name: str
    status: str
    activation_code: str | None
    activation_expires_at: datetime | None
    brand: str | None
    model: str | None
    android_version: str | None
    app_version: str | None
    created_at: datetime
    activated_at: datetime | None
    last_seen_at: datetime | None


def device_out(d: Device) -> DeviceOut:
    return DeviceOut(
        id=d.id,
        name=d.name,
        status=d.status,
        activation_code=d.activation_code,
        activation_expires_at=as_utc(d.activation_expires_at),
        brand=d.brand,
        model=d.model,
        android_version=d.android_version,
        app_version=d.app_version,
        created_at=as_utc(d.created_at),
        activated_at=as_utc(d.activated_at),
        last_seen_at=as_utc(d.last_seen_at),
    )


class ActivateIn(BaseModel):
    code: str = Field(min_length=4, max_length=16)
    brand: str | None = Field(default=None, max_length=64)
    model: str | None = Field(default=None, max_length=64)
    android_version: str | None = Field(default=None, max_length=32)
    app_version: str | None = Field(default=None, max_length=32)


class ActivateOut(BaseModel):
    device_id: str
    name: str
    token: str  # shown once; the server keeps only its hash


def make_router(Session, settings, require_admin) -> APIRouter:
    router = APIRouter()
    admin = [Depends(require_admin)]

    def server_url() -> str:
        return settings.public_url or f"http://{lan_ips()[0]}:{settings.port}"

    def get_device(s, device_id: str) -> Device:
        d = s.get(Device, device_id)
        if d is None:
            raise HTTPException(404, "device not found")
        return d

    def issue_code(d: Device) -> None:
        d.activation_code = new_activation_code()
        d.activation_expires_at = utcnow() + ACTIVATION_TTL

    # ---- admin -------------------------------------------------------------

    @router.get("/api/admin/devices", response_model=list[DeviceOut], dependencies=admin)
    def list_devices():
        with Session() as s:
            return [device_out(d) for d in s.scalars(select(Device).order_by(Device.created_at))]

    @router.post("/api/admin/devices", response_model=DeviceOut, dependencies=admin)
    def create_device(body: DeviceCreate):
        with Session() as s:
            d = Device(id=str(uuid.uuid4()), name=body.name.strip(), status=DeviceStatus.PENDING)
            issue_code(d)
            s.add(d)
            s.commit()
            return device_out(d)

    @router.post("/api/admin/devices/{device_id}/new-code", response_model=DeviceOut,
                 dependencies=admin)
    def renew_code(device_id: str):
        """New QR for an expired code, or to move a salesperson to a new phone.
        The old phone keeps working until the new one activates."""
        with Session() as s:
            d = get_device(s, device_id)
            if d.status == DeviceStatus.REVOKED:
                raise HTTPException(409, "device is revoked")
            issue_code(d)
            s.commit()
            return device_out(d)

    @router.post("/api/admin/devices/{device_id}/revoke", response_model=DeviceOut,
                 dependencies=admin)
    def revoke(device_id: str):
        with Session() as s:
            d = get_device(s, device_id)
            d.status = DeviceStatus.REVOKED
            d.token_hash = None
            d.activation_code = None
            d.activation_expires_at = None
            s.commit()
            return device_out(d)

    @router.get("/api/admin/devices/{device_id}/qr.svg", dependencies=admin)
    def qr(device_id: str):
        with Session() as s:
            d = get_device(s, device_id)
            if not d.activation_code:
                raise HTTPException(409, "no pending activation code")
            payload = json.dumps(
                {"v": 1, "server": server_url(), "code": d.activation_code}, separators=(",", ":")
            )
        svg = segno.make(payload, error="m").svg_inline(scale=6, border=2)
        return Response(svg, media_type="image/svg+xml", headers={"Cache-Control": "no-store"})

    @router.get("/api/admin/server-info", dependencies=admin)
    def server_info():
        others = [] if settings.public_url else [
            f"http://{ip}:{settings.port}" for ip in lan_ips()[1:]
        ]
        return {"server": server_url(), "public_url_configured": bool(settings.public_url),
                "other_addresses": others}

    # ---- phone -------------------------------------------------------------

    @router.post("/api/devices/activate", response_model=ActivateOut)
    def activate(body: ActivateIn):
        code = body.code.strip().upper()
        with Session() as s:
            d = s.scalars(select(Device).where(Device.activation_code == code)).first()
            expires = as_utc(d.activation_expires_at) if d else None
            if d is None or d.status == DeviceStatus.REVOKED or expires is None or expires < utcnow():
                raise HTTPException(400, "invalid or expired activation code")
            token = secrets.token_urlsafe(32)
            d.token_hash = hash_token(token)  # replaces any previous phone's token
            d.activation_code = None
            d.activation_expires_at = None
            d.status = DeviceStatus.ACTIVE
            d.activated_at = d.last_seen_at = utcnow()
            d.brand, d.model = body.brand, body.model
            d.android_version, d.app_version = body.android_version, body.app_version
            s.commit()
            return ActivateOut(device_id=d.id, name=d.name, token=token)

    return router


def make_require_device(Session):
    """Dependency: the active device behind the request's bearer token."""

    def require_device(request: Request) -> Device:
        auth = request.headers.get("authorization", "")
        scheme, _, token = auth.partition(" ")
        if scheme.lower() != "bearer" or not token:
            raise HTTPException(401, "missing device token")
        with Session() as s:
            d = s.scalars(select(Device).where(Device.token_hash == hash_token(token))).first()
            if d is None or d.status != DeviceStatus.ACTIVE:
                raise HTTPException(401, "invalid or revoked device token")
            d.last_seen_at = utcnow()
            s.commit()
            return d

    return require_device
