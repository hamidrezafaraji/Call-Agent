from sqlalchemy import create_engine, event, inspect, text
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
    _add_missing_columns(engine)
    return sessionmaker(engine, expire_on_commit=False)


def _add_missing_columns(engine) -> None:
    """Minimal upgrade path for installs at customers: add new nullable columns to old tables."""
    insp = inspect(engine)
    with engine.begin() as conn:
        for table in Base.metadata.sorted_tables:
            existing = {c["name"] for c in insp.get_columns(table.name)}
            for col in table.columns:
                if col.name not in existing and col.nullable:
                    ddl = col.type.compile(engine.dialect)
                    conn.execute(text(f'ALTER TABLE "{table.name}" ADD COLUMN "{col.name}" {ddl}'))
