from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker

from .config import Settings
from .models import Base


def make_session_factory(settings: Settings) -> sessionmaker:
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    settings.audio_dir.mkdir(parents=True, exist_ok=True)
    engine = create_engine(
        settings.db_url, connect_args={"check_same_thread": False, "timeout": 30}
    )

    @event.listens_for(engine, "connect")
    def _sqlite_pragmas(conn, _record):
        # WAL lets the API write while the worker is reading/writing.
        conn.execute("PRAGMA journal_mode=WAL")

    Base.metadata.create_all(engine)
    return sessionmaker(engine, expire_on_commit=False)
