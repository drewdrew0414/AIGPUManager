$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $root "gpu-mgr.jar"

if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    Write-Error "gpum jar not found at '$jar'. Place gpu-mgr.jar in the same directory as gpum.ps1."
    exit 1
}

& java --enable-native-access=ALL-UNNAMED -jar $jar @args
exit $LASTEXITCODE
