import os
import sys
from pathlib import Path

from . import speakers
from .config import Settings


def _add_cuda_dll_dirs() -> None:
    """On Windows, expose the pip-installed CUDA libraries (nvidia-cublas/cudnn) to CTranslate2."""
    if sys.platform != "win32":
        return
    try:
        import nvidia
    except ImportError:
        return
    for root in nvidia.__path__:
        for bin_dir in Path(root).glob("*/bin"):
            os.add_dll_directory(str(bin_dir))
            os.environ["PATH"] = f"{bin_dir}{os.pathsep}{os.environ['PATH']}"


class WhisperTranscriber:
    def __init__(self, settings: Settings):
        if settings.whisper_device == "cuda":
            _add_cuda_dll_dirs()
        from faster_whisper import WhisperModel

        self.language = settings.language
        self.model = WhisperModel(
            settings.whisper_model,
            device=settings.whisper_device,
            compute_type=settings.whisper_compute,
            download_root=str(settings.data_dir / "models"),
        )

    def transcribe(self, audio_file: Path) -> list[dict]:
        """Segments as [{"start": s, "end": s, "text": str, "speaker": "agent"|"customer"|None}]."""
        from faster_whisper import decode_audio

        audio = decode_audio(str(audio_file), sampling_rate=16_000)
        segments, _info = self.model.transcribe(
            audio,
            language=self.language,
            beam_size=5,
            vad_filter=True,  # skip silence; Whisper invents text on silent stretches
            condition_on_previous_text=False,  # avoids repetition loops on long calls
        )
        out = [
            {"start": round(seg.start, 2), "end": round(seg.end, 2), "text": seg.text.strip()}
            for seg in segments
            if seg.text.strip()
        ]
        levels = [speakers.loudness_db(audio, x["start"], x["end"]) for x in out]
        for x, who in zip(out, speakers.label(levels)):
            x["speaker"] = who
        return out
