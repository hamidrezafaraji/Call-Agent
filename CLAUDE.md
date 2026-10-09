# Call Agent

Standalone product (not part of APERP): collects phone calls from salespeople's Android phones,
stores them, and transcribes the Persian audio with Whisper. Will later be integrated with APERP
and other customers' CRMs through its REST API, so keep it CRM-agnostic.

- `server/` — Python 3.11 venv (`server/.venv`), FastAPI API (`python -m callagent`) and a separate
  transcription worker (`python -m callagent.worker`), SQLAlchemy + SQLite (`server/data/`, git-ignored).
- `android/` — Kotlin collector app (minSdk 26, AGP 8.7.3, Kotlin 2.0.21, no Compose). See android/README.md.
  Building needs a VPN (Google Maven is blocked in Iran). Gradle wrapper jar/scripts not generated yet.
- Tests: `cd server && .venv/Scripts/python -m pytest -q`
- Scope rule: v1 is deliberately minimal (call id, direction, time, transcript). Don't add features unasked.

## graphify

This project has a knowledge graph at graphify-out/.

- For codebase questions, first run `python -m graphify query "<question>"` (also `path "<A>" "<B>"`,
  `explain "<concept>"`) instead of reading many files.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review.
- After modifying code, run `PYTHONIOENCODING=utf-8 python -m graphify update .` from the project root
  (system Python 3.14 has graphify; the `graphify.exe` launcher is broken). `.graphifyignore` keeps the
  venv and data out of the graph.
