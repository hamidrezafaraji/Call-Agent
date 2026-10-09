from datetime import datetime, timezone

from sqlalchemy import DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Base(DeclarativeBase):
    pass


class Direction:
    INCOMING = "incoming"
    OUTGOING = "outgoing"
    ALL = (INCOMING, OUTGOING)


class Status:
    QUEUED = "queued"          # audio received, waiting for the worker
    PROCESSING = "processing"  # worker is transcribing
    DONE = "done"              # transcript ready
    FAILED = "failed"          # transcription error, see `error`
    NO_AUDIO = "no_audio"      # call logged without a recording


class DeviceStatus:
    PENDING = "pending"  # created by the admin, QR not scanned yet
    ACTIVE = "active"
    REVOKED = "revoked"


class Device(Base):
    """A salesperson's phone. Activated once by scanning a QR code."""

    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    name: Mapped[str] = mapped_column(String(100))  # e.g. the salesperson's name
    status: Mapped[str] = mapped_column(String(16), index=True)

    # one-time code inside the QR; cleared once used
    activation_code: Mapped[str | None] = mapped_column(String(16), unique=True)
    activation_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    # sha256 of the device's secret token; the token itself is only ever sent to the phone
    token_hash: Mapped[str | None] = mapped_column(String(64), unique=True)

    brand: Mapped[str | None] = mapped_column(String(64))
    model: Mapped[str | None] = mapped_column(String(64))
    android_version: Mapped[str | None] = mapped_column(String(32))
    app_version: Mapped[str | None] = mapped_column(String(32))

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    activated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class Call(Base):
    __tablename__ = "calls"

    # Unique call id generated on the phone, so re-uploads never duplicate a call.
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    device_id: Mapped[str | None] = mapped_column(ForeignKey("devices.id"), index=True)
    direction: Mapped[str] = mapped_column(String(16))
    phone_number: Mapped[str] = mapped_column(String(32), index=True)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    duration_sec: Mapped[int] = mapped_column(Integer, default=0)

    audio_path: Mapped[str | None] = mapped_column(String(512))
    transcript: Mapped[str | None] = mapped_column(Text)
    status: Mapped[str] = mapped_column(String(16), index=True)
    error: Mapped[str | None] = mapped_column(Text)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )
