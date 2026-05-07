@echo off
setlocal

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

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
"$p = [Environment]::GetEnvironmentVariable('Path','User'); ^
if ($p -notlike '*%INSTALL_DIR%*') { ^
  [Environment]::SetEnvironmentVariable('Path', $p + ';%INSTALL_DIR%', 'User') ^
}"

echo.
echo Installed gpum to:
echo %INSTALL_DIR%
echo.
echo IMPORTANT: Restart your terminal
echo Then run:
echo   gpum --help

endlocal
