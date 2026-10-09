import re
import secrets
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from sqlalchemy import select

from . import __version__
from .config import Settings, load_settings
from .db import make_session_factory
from .devices import make_require_device, make_router
from .models import Call, Device, Direction, Status
from .numbers import find_numbers

ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
AUDIO_EXTS = {".m4a", ".mp3", ".amr", ".wav", ".ogg", ".opus", ".aac", ".3gp", ".flac", ".webm"}


class NumberOut(BaseModel):
    type: str    # phone | card
    start: int   # character span inside `transcript` (code points; same as JS indexes for Persian text)
    end: int
    value: str   # digits to copy
    exact: bool  # False: digit count looks off, check the spoken text


class CallOut(BaseModel):
    id: str
    device_id: str | None
    direction: str
    phone_number: str
    started_at: datetime
    duration_sec: int
    has_audio: bool
    status: str
    transcript: str | None
    numbers: list[NumberOut]
    error: str | None


def to_out(call: Call) -> CallOut:
    started_at = call.started_at
    if started_at.tzinfo is None:  # SQLite drops the offset; values are stored in UTC
        started_at = started_at.replace(tzinfo=timezone.utc)
    return CallOut(
        id=call.id,
        device_id=call.device_id,
        direction=call.direction,
        phone_number=call.phone_number,
        started_at=started_at,
        duration_sec=call.duration_sec,
        has_audio=call.audio_path is not None,
        status=call.status,
        transcript=call.transcript,
        numbers=[
            NumberOut(type=n.type, start=n.start, end=n.end, value=n.value, exact=n.exact)
            for n in find_numbers(call.transcript or "")
        ],
        error=call.error,
    )


STATIC_DIR = Path(__file__).parent / "static"
AUDIO_TYPES = {".m4a": "audio/mp4", ".mp3": "audio/mpeg", ".amr": "audio/amr", ".wav": "audio/wav",
               ".ogg": "audio/ogg", ".opus": "audio/ogg", ".aac": "audio/aac", ".3gp": "audio/3gpp",
               ".flac": "audio/flac", ".webm": "audio/webm"}


def normalize_phone(number: str) -> str:
    """Iranian numbers in one form, so "+98912...", "0098912..." and "0912..." match."""
    n = re.sub(r"[\s\-()]", "", number.strip())
    if n.startswith("+98"):
        return "0" + n[3:]
    if n.startswith("0098"):
        return "0" + n[4:]
    return n


def resolve_admin_key(settings: Settings) -> str:
    """Admin key from settings, or one generated on first run and kept in the data dir."""
    if settings.admin_key:
        return settings.admin_key
    path = settings.data_dir / "admin.key"
    if not path.exists():
        settings.data_dir.mkdir(parents=True, exist_ok=True)
        path.write_text(secrets.token_urlsafe(24), encoding="utf-8")
        print(f"Generated admin key, saved in {path}")
    return path.read_text(encoding="utf-8").strip()


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or load_settings()
    Session = make_session_factory(settings)
    app = FastAPI(title="Call Agent", version=__version__)
    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

    admin_key = resolve_admin_key(settings)

    def require_admin(x_api_key: Annotated[str | None, Header()] = None) -> None:
        if not secrets.compare_digest(x_api_key or "", admin_key):
            raise HTTPException(401, "invalid API key")

    require_device = make_require_device(Session)
    auth = [Depends(require_admin)]
    app.include_router(make_router(Session, settings, require_admin))

    @app.get("/admin", include_in_schema=False)
    def admin_page():
        return FileResponse(STATIC_DIR / "admin.html")

    @app.get("/health")
    def health():
        return {"ok": True, "version": __version__}

    @app.post("/api/calls", response_model=CallOut)
    def upload_call(
        device: Annotated[Device, Depends(require_device)],
        id: Annotated[str, Form()],
        direction: Annotated[str, Form()],
        phone_number: Annotated[str, Form()],
        started_at: Annotated[datetime, Form()],
        duration_sec: Annotated[int, Form(ge=0)] = 0,
        audio: Annotated[UploadFile | None, File()] = None,
    ):
        if not ID_RE.match(id):
            raise HTTPException(422, "id must be 1-64 chars of letters, digits, - or _")
        if direction not in Direction.ALL:
            raise HTTPException(422, f"direction must be one of {Direction.ALL}")
        if started_at.tzinfo is None:
            raise HTTPException(422, "started_at must include a timezone offset")
        started_at = started_at.astimezone(timezone.utc)

        with Session() as s:
            existing = s.get(Call, id)
            if existing and existing.device_id != device.id:
                raise HTTPException(409, "call id already used by another device")
            # Re-upload of a known call: accept it only to attach missing audio.
            if existing and (existing.audio_path or audio is None):
                return to_out(existing)

            audio_path = None
            if audio is not None and audio.filename:
                ext = Path(audio.filename).suffix.lower()
                if ext not in AUDIO_EXTS:
                    raise HTTPException(422, f"unsupported audio type {ext!r}")
                rel = Path(f"{started_at:%Y}") / f"{started_at:%m}" / f"{id}{ext}"
                dest = settings.audio_dir / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                with dest.open("wb") as f:
                    shutil.copyfileobj(audio.file, f)
                audio_path = rel.as_posix()

            call = existing or Call(id=id, device_id=device.id)
            call.direction = direction
            call.phone_number = normalize_phone(phone_number)
            call.started_at = started_at
            call.duration_sec = duration_sec
            call.audio_path = audio_path
            call.status = Status.QUEUED if audio_path else Status.NO_AUDIO
            call.error = None
            s.add(call)
            s.commit()
            return to_out(call)

    @app.get("/api/calls", response_model=list[CallOut], dependencies=auth)
    def list_calls(
        phone_number: str | None = None,
        status: str | None = None,
        device_id: str | None = None,
        limit: Annotated[int, Query(ge=1, le=500)] = 100,
        offset: Annotated[int, Query(ge=0)] = 0,
    ):
        q = select(Call).order_by(Call.started_at.desc()).limit(limit).offset(offset)
        if phone_number:
            q = q.where(Call.phone_number == normalize_phone(phone_number))
        if status:
            q = q.where(Call.status == status)
        if device_id:
            q = q.where(Call.device_id == device_id)
        with Session() as s:
            return [to_out(c) for c in s.scalars(q)]

    @app.get("/api/calls/{call_id}", response_model=CallOut, dependencies=auth)
    def get_call(call_id: str):
        with Session() as s:
            call = s.get(Call, call_id)
            if call is None:
                raise HTTPException(404, "call not found")
            return to_out(call)

    @app.get("/api/calls/{call_id}/audio", dependencies=auth)
    def get_call_audio(call_id: str):
        with Session() as s:
            call = s.get(Call, call_id)
            if call is None or not call.audio_path:
                raise HTTPException(404, "no audio for this call")
            path = settings.audio_dir / call.audio_path
        if not path.is_file():
            raise HTTPException(404, "audio file missing")
        return FileResponse(path, media_type=AUDIO_TYPES.get(path.suffix.lower(), "application/octet-stream"))

    return app
