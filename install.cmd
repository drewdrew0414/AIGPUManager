@echo off
setlocal EnableExtensions

:: -----------------------------
:: Config
:: -----------------------------
set "RELEASE_VERSION_URL=%GPUM_RELEASE_VERSION_URL%"
if not defined RELEASE_VERSION_URL set "RELEASE_VERSION_URL=https://github.com/drewdrew0414/AIGPUManager/releases/latest/download/gpum-version.txt"

set "RELEASE_JAR_URL=%GPUM_RELEASE_JAR_URL%"
if not defined RELEASE_JAR_URL set "RELEASE_JAR_URL=https://github.com/drewdrew0414/AIGPUManager/releases/latest/download/gpu-mgr.jar"

set "INSTALL_DIR=%GPUM_INSTALL_DIR%"
if not defined INSTALL_DIR set "INSTALL_DIR=%LOCALAPPDATA%\gpum"

set "TARGET_JAR=%INSTALL_DIR%\gpu-mgr.jar"
set "TARGET_CMD=%INSTALL_DIR%\gpum.cmd"

:: -----------------------------
:: Requirements
:: -----------------------------
where java >nul 2>nul || (
  echo ERROR: java not found in PATH
  exit /b 1
)

where curl >nul 2>nul || (
  echo ERROR: curl not found in PATH
  echo Windows 10+ should include curl by default.
  exit /b 1
)

if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"

:: -----------------------------
:: Fetch remote version (curl)
:: -----------------------------
for /f "delims=" %%V in ('curl -L --fail "%RELEASE_VERSION_URL%"') do set "REMOTE_VERSION=%%V"

if not defined REMOTE_VERSION (
  echo ERROR: failed to fetch remote version
  exit /b 1
)

:: trim spaces + remove CR
for /f "tokens=* delims= " %%A in ("%REMOTE_VERSION%") do set "REMOTE_VERSION=%%A"

:: -----------------------------
:: Local version
:: -----------------------------
if exist "%TARGET_JAR%" (
  for /f "tokens=2 delims= " %%V in ('java --enable-native-access=ALL-UNNAMED -jar "%TARGET_JAR%" --version 2^>nul') do set "LOCAL_VERSION=%%V"
)

:: -----------------------------
:: Version compare (PowerShell only for logic)
:: -----------------------------
if defined LOCAL_VERSION (
  for /f "usebackq delims=" %%R in (`
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$l=[Version]('%LOCAL_VERSION%'.TrimStart('v')); ^
     $r=[Version]('%REMOTE_VERSION%'.TrimStart('v')); ^
     if ($l -ge $r) { 'GE' } else { 'LT' }"
  `) do set "CMP=%%R"

  if /i "%CMP%"=="GE" (
    echo Already up-to-date (%LOCAL_VERSION%)
    goto :write_launcher
  )

  choice /M "Upgrade %LOCAL_VERSION% -> %REMOTE_VERSION% ?"
  if errorlevel 2 exit /b 0
)

:: -----------------------------
:: Download (curl only)
:: -----------------------------
echo Downloading gpum %REMOTE_VERSION%...

curl -L --fail "%RELEASE_JAR_URL%" -o "%TARGET_JAR%.tmp"
if errorlevel 1 (
  echo ERROR: download failed
  exit /b 1
)

move /Y "%TARGET_JAR%.tmp" "%TARGET_JAR%" >nul

:: -----------------------------
:: Launcher
:: -----------------------------
:write_launcher
(
echo @echo off
echo setlocal
echo set "GPUM_JAR=%%~dp0gpu-mgr.jar"
echo if not exist "%%GPUM_JAR%%" ^(
echo   echo ERROR: gpum jar not found.
echo   exit /b 1
echo ^)
echo java --enable-native-access=ALL-UNNAMED -jar "%%GPUM_JAR%%" %%*
) > "%TARGET_CMD%"

:: -----------------------------
:: PATH (safe)
:: -----------------------------
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$dir='%INSTALL_DIR%'; ^
   $p=[Environment]::GetEnvironmentVariable('Path','User'); ^
   if ($p -notlike '*'+$dir+'*') { ^
     [Environment]::SetEnvironmentVariable('Path',$p+';'+$dir,'User'); ^
     Write-Host 'Added to PATH'; ^
   }"

:: -----------------------------
:: Done
:: -----------------------------
set "PATH=%INSTALL_DIR%;%PATH%"

echo.
echo Installed gpum %REMOTE_VERSION%
echo Run: gpum --help
echo (Restart terminal if needed)

endlocal
