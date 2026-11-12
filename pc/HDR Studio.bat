@echo off
cd /d "%~dp0"
if not exist .venv\Scripts\pythonw.exe (
  echo Setting up HDR Studio for the first time...
  python -m venv .venv || goto :err
  .venv\Scripts\python.exe -m pip install -r requirements.txt || goto :err
)
start "" .venv\Scripts\pythonw.exe -m hdrstudio
exit /b 0
:err
echo Setup failed. Python 3.11+ is required.
pause
