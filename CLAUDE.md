# Call Agent

Standalone product (not part of APERP): collects phone calls from salespeople's Android phones,
stores them, and transcribes the Persian audio with Whisper. Will later be integrated with APERP
and other customers' CRMs through its REST API, so keep it CRM-agnostic.

- `server/` — Python 3.11 venv (`server/.venv`), FastAPI API (`python -m callagent`) and a separate
  transcription worker (`python -m callagent.worker`), SQLAlchemy + SQLite (`server/data/`, git-ignored).
- `android/` — Kotlin collector app (minSdk 26, compileSdk 37, AGP 9.4.1 with built-in Kotlin, Gradle 9.8.1,
  no Compose). See android/README.md. Build: `cd android && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
  ./gradlew.bat assembleDebug testDebugUnitTest` (needs VPN: Google Maven is blocked in Iran). APKs for testing go
  to `builds/` (git-ignored).
- `start.bat` — one-click start for Windows (venv, deps, server + worker windows, opens /admin). Avoid certutil /
  Invoke-WebRequest in scripts and in shell commands: Defender flags them (Trojan:Win32/Ceprolad.A) and blocks the command.
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
