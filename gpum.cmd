@echo off
setlocal

set "GPUM_ROOT=%~dp0"
set "GPUM_TARGET_JAR=%GPUM_ROOT%gpu-mgr.jar"

if not exist "%GPUM_TARGET_JAR%" (
  echo ERROR: gpum jar not found at "%GPUM_TARGET_JAR%".
  echo Place gpu-mgr.jar in the same directory as gpum.cmd
  exit /b 1
)

java --enable-native-access=ALL-UNNAMED -jar "%GPUM_TARGET_JAR%" %*
