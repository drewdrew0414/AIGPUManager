@echo off
setlocal ENABLEDELAYEDEXPANSION

set "INSTALL_DIR=%LOCALAPPDATA%\gpum"
set "TARGET_JAR=%INSTALL_DIR%\gpu-mgr.jar"
set "LAUNCHER=%INSTALL_DIR%\gpum.cmd"

set "SOURCE_DIR=%~dp0"
set "SOURCE_JAR=%SOURCE_DIR%gpu-mgr.jar"

if not exist "%SOURCE_JAR%" (
  echo ERROR: gpu-mgr.jar not found in "%SOURCE_DIR%"
  exit /b 1
)

if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"

copy /Y "%SOURCE_JAR%" "%TARGET_JAR%" >nul

(
echo @echo off
echo setlocal
echo set "GPUM_JAR=%%~dp0gpu-mgr.jar"
echo if not exist "%%GPUM_JAR%%" ^(
echo   echo ERROR: gpum jar not found.
echo   exit /b 1
echo ^)
echo java -jar "%%GPUM_JAR%%" %%*
) > "%LAUNCHER%"
echo %PATH% | find /I "%INSTALL_DIR%" >nul
if errorlevel 1 (
  echo Adding to PATH...
  setx PATH "%PATH%;%INSTALL_DIR%" >nul
  echo PATH updated. Restart your terminal.
)

echo.
echo Installed gpum
echo Run: gpum --help

endlocal
