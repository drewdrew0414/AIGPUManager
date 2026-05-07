@echo off
setlocal EnableExtensions

set "RELEASE_VERSION_URL=%GPUM_RELEASE_VERSION_URL%"
if not defined RELEASE_VERSION_URL set "RELEASE_VERSION_URL=https://github.com/drewdrew0414/AIGPUManager/releases/latest/download/gpum-version.txt"

set "RELEASE_JAR_URL=%GPUM_RELEASE_JAR_URL%"
if not defined RELEASE_JAR_URL set "RELEASE_JAR_URL=https://github.com/drewdrew0414/AIGPUManager/releases/latest/download/gpu-mgr.jar"

set "INSTALL_DIR=%GPUM_INSTALL_DIR%"
if not defined INSTALL_DIR set "INSTALL_DIR=%LocalAppData%\gpum"

set "TARGET_JAR=%INSTALL_DIR%\gpu-mgr.jar"
set "TARGET_CMD=%INSTALL_DIR%\gpum.cmd"
set "REMOTE_VERSION="
set "LOCAL_VERSION="
set "COMPARE_RESULT="

where java >nul 2>nul
if errorlevel 1 (
  echo ERROR: java was not found in PATH.
  exit /b 1
)

where powershell >nul 2>nul
if errorlevel 1 (
  echo ERROR: powershell was not found in PATH.
  exit /b 1
)

if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"

for /f "usebackq delims=" %%V in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "(Invoke-WebRequest -UseBasicParsing '%RELEASE_VERSION_URL%').Content.Trim()"`) do set "REMOTE_VERSION=%%V"
if not defined REMOTE_VERSION (
  echo ERROR: failed to read remote version from %RELEASE_VERSION_URL%
  exit /b 1
)

if exist "%TARGET_JAR%" (
  for /f "tokens=2 delims= " %%V in ('java --enable-native-access=ALL-UNNAMED -jar "%TARGET_JAR%" --version 2^>nul') do set "LOCAL_VERSION=%%V"
)

if defined LOCAL_VERSION (
  for /f "usebackq delims=" %%R in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$local=[Version]('%LOCAL_VERSION%'.TrimStart('v')); $remote=[Version]('%REMOTE_VERSION%'.TrimStart('v')); if ($local -ge $remote) { 'GE' } else { 'LT' }"`) do set "COMPARE_RESULT=%%R"
  if /i "%COMPARE_RESULT%"=="GE" (
    echo gpum %LOCAL_VERSION% is already installed at "%TARGET_JAR%"
    echo Remote release %REMOTE_VERSION% is not newer. Skipping download.
    call :write_launcher
    call :ensure_path
    goto :success
  )

  choice /M "Installed version %LOCAL_VERSION% is older than remote version %REMOTE_VERSION%. Upgrade"
  if errorlevel 2 (
    echo Cancelled.
    exit /b 0
  )
)

echo Downloading gpum %REMOTE_VERSION% ...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing '%RELEASE_JAR_URL%' -OutFile '%TARGET_JAR%.tmp'; Move-Item -Force '%TARGET_JAR%.tmp' '%TARGET_JAR%'"
if errorlevel 1 (
  echo ERROR: failed to download %RELEASE_JAR_URL%
  if exist "%TARGET_JAR%.tmp" del /q "%TARGET_JAR%.tmp" >nul 2>nul
  exit /b 1
)

call :write_launcher
call :ensure_path

:success
set "PATH=%INSTALL_DIR%;%PATH%"
echo Installed gpum %REMOTE_VERSION%
echo Launcher: %TARGET_CMD%
echo Jar: %TARGET_JAR%
echo Run: gpum --help
exit /b 0

:write_launcher
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$content=@' ^
@echo off ^
setlocal ^
set ""GPUM_ROOT=%%~dp0"" ^
set ""GPUM_TARGET_JAR=%%GPUM_ROOT%%gpu-mgr.jar"" ^
if not exist ""%%GPUM_TARGET_JAR%%"" ( ^
  echo ERROR: gpum jar not found at ""%%GPUM_TARGET_JAR%%"". ^
  echo Place gpu-mgr.jar in the same directory as gpum.cmd ^
  exit /b 1 ^
) ^
java --enable-native-access=ALL-UNNAMED -jar ""%%GPUM_TARGET_JAR%%"" %%* ^
'@; Set-Content -LiteralPath '%TARGET_CMD%' -Value $content -Encoding ASCII"
if errorlevel 1 (
  echo ERROR: failed to write launcher %TARGET_CMD%
  exit /b 1
)
exit /b 0

:ensure_path
if /i "%GPUM_SKIP_PATH_UPDATE%"=="1" (
  echo Skipping PATH update because GPUM_SKIP_PATH_UPDATE=1
  exit /b 0
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$installDir=[IO.Path]::GetFullPath('%INSTALL_DIR%');" ^
  "$userPath=[Environment]::GetEnvironmentVariable('Path','User');" ^
  "$parts=@(); if ($userPath) { $parts=$userPath -split ';' | Where-Object { $_ -and $_.Trim() } };" ^
  "$exists=$false; foreach ($part in $parts) { if ($part.TrimEnd('\') -ieq $installDir.TrimEnd('\')) { $exists=$true; break } };" ^
  "if (-not $exists) {" ^
  "  $newPath=if ($userPath -and $userPath.Trim()) { $userPath + ';' + $installDir } else { $installDir };" ^
  "  [Environment]::SetEnvironmentVariable('Path',$newPath,'User');" ^
  "  $signature='[System.Runtime.InteropServices.DllImport(""user32.dll"", SetLastError=true, CharSet=System.Runtime.InteropServices.CharSet.Auto)] public static extern System.IntPtr SendMessageTimeout(System.IntPtr hWnd, uint Msg, System.UIntPtr wParam, string lParam, uint fuFlags, uint uTimeout, out System.UIntPtr lpdwResult);';" ^
  "  $type=Add-Type -MemberDefinition $signature -Name Win32SendMessageTimeout -Namespace GPUM -PassThru;" ^
  "  [UIntPtr]$result=[UIntPtr]::Zero;" ^
  "  $null=$type::SendMessageTimeout([IntPtr]0xffff,0x1A,[UIntPtr]::Zero,'Environment',2,5000,[ref]$result);" ^
  "  Write-Host ('Added ' + $installDir + ' to the user PATH.');" ^
  "} else { Write-Host ('PATH already contains ' + $installDir + '.'); }"
if errorlevel 1 (
  echo ERROR: failed to update the user PATH
  exit /b 1
)
exit /b 0
