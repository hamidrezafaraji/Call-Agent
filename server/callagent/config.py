import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

SERVER_DIR = Path(__file__).resolve().parent.parent
load_dotenv(SERVER_DIR / ".env")


@dataclass(frozen=True)
class Settings:
    api_key: str
    data_dir: Path
    whisper_model: str
    whisper_device: str
    whisper_compute: str
    language: str
    poll_seconds: float

    @property
    def db_url(self) -> str:
        return f"sqlite:///{(self.data_dir / 'callagent.db').as_posix()}"

    @property
    def audio_dir(self) -> Path:
        return self.data_dir / "audio"


def load_settings() -> Settings:
    data_dir = Path(os.getenv("CALLAGENT_DATA_DIR", "data"))
    if not data_dir.is_absolute():
        data_dir = SERVER_DIR / data_dir
    return Settings(
        api_key=os.getenv("CALLAGENT_API_KEY", ""),
        data_dir=data_dir,
        whisper_model=os.getenv("CALLAGENT_WHISPER_MODEL", "large-v3"),
        whisper_device=os.getenv("CALLAGENT_WHISPER_DEVICE", "cuda"),
        whisper_compute=os.getenv("CALLAGENT_WHISPER_COMPUTE", "float16"),
        language=os.getenv("CALLAGENT_LANGUAGE", "fa"),
        poll_seconds=float(os.getenv("CALLAGENT_POLL_SECONDS", "5")),
    )
