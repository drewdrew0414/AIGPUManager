#requires -Version 5.1

$ErrorActionPreference = "Stop"

# -----------------------------
# Config
# -----------------------------
$RELEASE_VERSION_URL = if ($env:GPUM_RELEASE_VERSION_URL) {
    $env:GPUM_RELEASE_VERSION_URL
} else {
    "https://github.com/drewdrew0414/AIGPUManager/releases/latest/download/gpum-version.txt"
}

$RELEASE_JAR_URL = if ($env:GPUM_RELEASE_JAR_URL) {
    $env:GPUM_RELEASE_JAR_URL
} else {
    "https://github.com/drewdrew0414/AIGPUManager/releases/latest/download/gpu-mgr.jar"
}

$INSTALL_DIR = if ($env:GPUM_INSTALL_DIR) {
    $env:GPUM_INSTALL_DIR
} else {
    Join-Path $env:LOCALAPPDATA "gpum"
}

$TARGET_JAR = Join-Path $INSTALL_DIR "gpu-mgr.jar"
$TARGET_CMD = Join-Path $INSTALL_DIR "gpum.cmd"

# -----------------------------
# Requirements
# -----------------------------
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error "java not found in PATH"
    exit 1
}

if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
    Write-Error "curl not found in PATH"
    exit 1
}

New-Item -ItemType Directory -Force -Path $INSTALL_DIR | Out-Null

# -----------------------------
# Helpers
# -----------------------------
function Normalize-Version {
    param([string]$v)

    if (-not $v) {
        return ""
    }

    return $v.Trim().TrimStart("v")
}

function Get-InstalledVersion {
    if (-not (Test-Path $TARGET_JAR)) {
        return $null
    }

    try {
        $out = & java --enable-native-access=ALL-UNNAMED -jar $TARGET_JAR --version 2>$null

        if ($LASTEXITCODE -ne 0) {
            return $null
        }

        $firstLine = ($out | Select-Object -First 1)

        if ($firstLine -match '^\S+\s+(.+)$') {
            return Normalize-Version $matches[1]
        }

        return $null
    }
    catch {
        return $null
    }
}

function Write-Launcher {
@"
@echo off
setlocal

set "GPUM_JAR=%~dp0gpu-mgr.jar"

if not exist "%GPUM_JAR%" (
  echo ERROR: gpum jar not found.
  exit /b 1
)

java --enable-native-access=ALL-UNNAMED -jar "%GPUM_JAR%" %*

"@ | Set-Content -Encoding ASCII $TARGET_CMD
}

function Ensure-Path {
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")

    if (-not $userPath) {
        $userPath = ""
    }

    $normalizedInstall = $INSTALL_DIR.TrimEnd("\")

    $parts = $userPath.Split(";") | Where-Object {
        $_.Trim()
    }

    foreach ($part in $parts) {
        if ($part.TrimEnd("\") -ieq $normalizedInstall) {
            return
        }
    }

    $newPath = if ($userPath.Trim()) {
        "$userPath;$INSTALL_DIR"
    } else {
        $INSTALL_DIR
    }

    [Environment]::SetEnvironmentVariable(
        "Path",
        $newPath,
        "User"
    )

    Write-Host "Added $INSTALL_DIR to PATH"
    Write-Host "Restart terminal to apply changes"
}

# -----------------------------
# Fetch remote version
# -----------------------------
try {
    $REMOTE_VERSION = curl.exe -fsSL $RELEASE_VERSION_URL
    $REMOTE_VERSION = Normalize-Version $REMOTE_VERSION
}
catch {
    Write-Error "Failed to fetch remote version"
    exit 1
}

if (-not $REMOTE_VERSION) {
    Write-Error "Remote version is empty"
    exit 1
}

# -----------------------------
# Local version
# -----------------------------
$LOCAL_VERSION = Get-InstalledVersion

# -----------------------------
# Version compare
# -----------------------------
if ($LOCAL_VERSION) {

    try {
        $localVer  = [Version](Normalize-Version $LOCAL_VERSION)
        $remoteVer = [Version](Normalize-Version $REMOTE_VERSION)

        if ($localVer -ge $remoteVer) {
            Write-Host "Already up-to-date ($LOCAL_VERSION)"

            Write-Launcher
            Ensure-Path
            exit 0
        }
    }
    catch {
        Write-Warning "Version comparison failed. Continuing with install."
    }

    $answer = Read-Host "Upgrade $LOCAL_VERSION -> $REMOTE_VERSION ? [y/N]"

    if ($answer -notmatch '^(y|yes)$') {
        Write-Host "Cancelled."
        exit 0
    }
}

# -----------------------------
# Download
# -----------------------------
Write-Host "Downloading gpum $REMOTE_VERSION..."

$tmp = "$TARGET_JAR.tmp"

try {
    curl.exe -fsSL $RELEASE_JAR_URL -o $tmp

    if (-not (Test-Path $tmp)) {
        throw "Download failed"
    }

    Move-Item -Force $tmp $TARGET_JAR
}
catch {
    if (Test-Path $tmp) {
        Remove-Item -Force $tmp
    }

    Write-Error "Failed to download jar"
    exit 1
}

# -----------------------------
# Write launcher + PATH
# -----------------------------
Write-Launcher
Ensure-Path

# -----------------------------
# Done
# -----------------------------
Write-Host ""
Write-Host "Installed gpum $REMOTE_VERSION"
Write-Host "Launcher: $TARGET_CMD"
Write-Host "Jar: $TARGET_JAR"
Write-Host ""
Write-Host "Run:"
Write-Host "  gpum --help"
