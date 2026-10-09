@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0server"

for /F %%a in ('echo prompt $E^|cmd') do set "ESC=%%a"
set "CLR_RESET=%ESC%[0m"
set "CLR_GREEN=%ESC%[92m"
set "CLR_WHITE=%ESC%[97m"
set "CLR_RED=%ESC%[91m"
set "CLR_YELLOW=%ESC%[93m"

rem -- separator lines as wide as the console --
set "COLS=78"
for /f "usebackq delims=" %%W in (`powershell -NoProfile -Command "$Host.UI.RawUI.WindowSize.Width" 2^>nul`) do set "COLS=%%W"
if "%COLS%"=="" set "COLS=78"
set "LINE="
for /L %%i in (1,1,%COLS%) do set "LINE=!LINE!-"

rem -- version comes from callagent\__init__.py --
set "APP_VERSION=?"
for /f "tokens=2 delims==" %%V in ('findstr /b "__version__" callagent\__init__.py') do set "APP_VERSION=%%~V"
set "APP_VERSION=%APP_VERSION: =%"
set "APP_VERSION=%APP_VERSION:"=%"

set "TITLE_TEXT=welcome to Call Agent Ver %APP_VERSION%"
set "TITLE_LEN=35"
for /f "usebackq delims=" %%L in (`powershell -NoProfile -Command "('%TITLE_TEXT%').Length" 2^>nul`) do set "TITLE_LEN=%%L"
set /a "TITLE_PAD=(COLS-TITLE_LEN)/2"
if %TITLE_PAD% LSS 0 set "TITLE_PAD=0"
set "TITLE_PADSTR="
for /L %%i in (1,1,%TITLE_PAD%) do set "TITLE_PADSTR=!TITLE_PADSTR! "

echo.
echo %CLR_WHITE%%LINE%%CLR_RESET%
echo %CLR_WHITE%%TITLE_PADSTR%%TITLE_TEXT%%CLR_RESET%
echo %CLR_WHITE%%LINE%%CLR_RESET%
echo.
echo %CLR_GREEN%The first run (or the first run after an update) takes longer. Please wait...%CLR_RESET%

rem -- Python 3.11 virtual environment (faster-whisper does not support newer Pythons yet) --
set "PY=.venv\Scripts\python.exe"
if not exist "%PY%" (
    py -3.11 -m venv .venv
    if errorlevel 1 (
        echo.
        echo %CLR_RED%Could not create the Python 3.11 environment. Install Python 3.11 from python.org and try again.%CLR_RESET%
        pause
        exit /b 1
    )
)

rem -- run pip only when requirements.txt changed since the last successful install --
if not exist "data" mkdir "data"
set "REQ_HASH="
rem    hashed with Python on purpose: antivirus heuristics flag scripts that call certutil
for /f "delims=" %%H in ('%PY% -c "import hashlib;print(hashlib.sha256(open('requirements.txt','rb').read()).hexdigest())"') do set "REQ_HASH=%%H"
set "OLD_HASH="
if exist "data\.requirements.sha256" set /p OLD_HASH=<"data\.requirements.sha256"
if defined REQ_HASH if "!REQ_HASH!"=="!OLD_HASH!" (
    "%PY%" -c "import fastapi, uvicorn, sqlalchemy, segno, faster_whisper" >nul 2>&1
    if not errorlevel 1 goto :deps_ok
)

echo %CLR_GREEN%Installing libraries (needs internet, may take several minutes)...%CLR_RESET%
set "PIP_LOG=%TEMP%\callagent_pip_%RANDOM%.log"
"%PY%" -m pip install -r requirements.txt --quiet --disable-pip-version-check --log "%PIP_LOG%" >nul 2>&1
if errorlevel 1 (
    echo.
    type "%PIP_LOG%"
    del "%PIP_LOG%" >nul 2>nul
    echo.
    echo %CLR_RED%Error installing libraries. Make sure this computer is connected to the internet.%CLR_RESET%
    pause
    exit /b 1
)
del "%PIP_LOG%" >nul 2>nul
if defined REQ_HASH >"data\.requirements.sha256" echo !REQ_HASH!

:deps_ok

rem -- no NVIDIA GPU: transcribe on the CPU (overrides CALLAGENT_WHISPER_* in .env) --
where nvidia-smi >nul 2>&1
if errorlevel 1 (
    set "CALLAGENT_WHISPER_DEVICE=cpu"
    set "CALLAGENT_WHISPER_COMPUTE=int8"
    echo %CLR_YELLOW%No NVIDIA graphics card found: speech-to-text will run on the CPU, slower.%CLR_RESET%
)

set "PORT=8100"
if exist ".env" for /f "tokens=2 delims==" %%P in ('findstr /b "CALLAGENT_PORT=" .env') do if not "%%P"=="" set "PORT=%%P"

echo %CLR_WHITE%%LINE%%CLR_RESET%
echo.
echo   Starting the server and the speech-to-text worker...
echo   (Keep both windows open; closing them stops Call Agent.)
echo   The first transcription downloads the Whisper model (about 3 GB).
echo.

start "Call Agent Server v%APP_VERSION%" cmd /k ""%PY%" -m callagent"
start "Call Agent Speech-to-Text v%APP_VERSION%" cmd /k ""%PY%" -m callagent.worker"

rem -- wait for the server (it also creates the admin key on its first run) --
set /a "TRIES=0"
:wait_server
timeout /t 1 /nobreak >nul
set /a "TRIES+=1"
"%PY%" -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:%PORT%/health',timeout=2)" >nul 2>&1
if errorlevel 1 if !TRIES! LSS 30 goto :wait_server

echo   Admin page:  %CLR_WHITE%http://127.0.0.1:%PORT%/admin%CLR_RESET%
if exist "data\admin.key" (
    set /p ADMIN_KEY=<"data\admin.key"
    echo   Admin key:   %CLR_WHITE%!ADMIN_KEY!%CLR_RESET%
)
echo.
echo %CLR_YELLOW%  Phones reach this server over the network: Windows Firewall must allow port %PORT%.%CLR_RESET%
echo.

start "" "http://127.0.0.1:%PORT%/admin"

pause
