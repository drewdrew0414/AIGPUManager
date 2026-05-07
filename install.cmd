@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "SCRIPT_PATH=%SCRIPT_DIR%install-window.ps1"

if not exist "%SCRIPT_PATH%" (
  echo ERROR: install-window.ps1 was not found next to install.cmd
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_PATH%" %*
exit /b %ERRORLEVEL%
