"""Background worker: picks queued calls and fills in their transcripts.

Run with:  python -m callagent.worker
"""
import logging
import time

from sqlalchemy import select, update
from sqlalchemy.orm import sessionmaker

from .config import Settings, load_settings
from .db import make_session_factory
from .models import Call, Status

log = logging.getLogger("callagent.worker")


def reset_stale(Session: sessionmaker) -> None:
    """Calls left 'processing' by a crashed worker go back to the queue."""
    with Session() as s:
        s.execute(update(Call).where(Call.status == Status.PROCESSING).values(status=Status.QUEUED))
        s.commit()


def claim_next(Session: sessionmaker) -> Call | None:
    with Session() as s:
        call = s.scalars(
            select(Call).where(Call.status == Status.QUEUED).order_by(Call.created_at).limit(1)
        ).first()
        if call is None:
            return None
        claimed = s.execute(
            update(Call)
            .where(Call.id == call.id, Call.status == Status.QUEUED)
            .values(status=Status.PROCESSING)
        ).rowcount
        s.commit()
        return call if claimed else None


def process_one(Session: sessionmaker, settings: Settings, transcriber) -> bool:
    """Transcribe one queued call. Returns False when the queue is empty."""
    call = claim_next(Session)
    if call is None:
        return False
    log.info("transcribing %s (%ss)", call.id, call.duration_sec)
    started = time.monotonic()
    try:
        text = transcriber.transcribe(settings.audio_dir / call.audio_path)
        values = {"status": Status.DONE, "transcript": text, "error": None}
        log.info("done %s in %.1fs", call.id, time.monotonic() - started)
    except Exception as e:  # keep the worker alive; the error is stored on the call
        log.exception("failed %s", call.id)
        values = {"status": Status.FAILED, "error": f"{type(e).__name__}: {e}"}
    with Session() as s:
        s.execute(update(Call).where(Call.id == call.id).values(**values))
        s.commit()
    return True


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    settings = load_settings()
    Session = make_session_factory(settings)
    reset_stale(Session)

    from .transcriber import WhisperTranscriber

    log.info("loading Whisper %s on %s ...", settings.whisper_model, settings.whisper_device)
    transcriber = WhisperTranscriber(settings)
    log.info("worker ready")
    while True:
        if not process_one(Session, settings, transcriber):
            time.sleep(settings.poll_seconds)


if __name__ == "__main__":
    main()
