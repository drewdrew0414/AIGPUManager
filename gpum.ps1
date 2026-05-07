$ErrorActionPreference = "Stop"

$gpumRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gpumJar = Join-Path $gpumRoot "gpu-mgr.jar"

if (-not (Test-Path $gpumJar)) {
    Write-Error "gpum jar not found. Place gpu-mgr.jar in the same directory as gpum.ps1"
}

& java --enable-native-access=ALL-UNNAMED -jar $gpumJar @args
