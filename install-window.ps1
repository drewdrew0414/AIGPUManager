$ErrorActionPreference = "Stop"

$ReleaseVersionUrl = if ($env:GPUM_RELEASE_VERSION_URL) {
    $env:GPUM_RELEASE_VERSION_URL
} else {
    "https://github.com/drewdrew0414/AIGPUManager/releases/latest/download/gpum-version.txt"
}

$ReleaseJarUrl = if ($env:GPUM_RELEASE_JAR_URL) {
    $env:GPUM_RELEASE_JAR_URL
} else {
    "https://github.com/drewdrew0414/AIGPUManager/releases/latest/download/gpu-mgr.jar"
}

$ReleaseCmdUrl = if ($env:GPUM_RELEASE_CMD_URL) {
    $env:GPUM_RELEASE_CMD_URL
} else {
    "https://github.com/drewdrew0414/AIGPUManager/releases/latest/download/gpum.cmd"
}

$ReleasePs1Url = if ($env:GPUM_RELEASE_PS1_URL) {
    $env:GPUM_RELEASE_PS1_URL
} else {
    "https://github.com/drewdrew0414/AIGPUManager/releases/latest/download/gpum.ps1"
}

$InstallDir = if ($env:GPUM_INSTALL_DIR) {
    $env:GPUM_INSTALL_DIR
} else {
    Join-Path $env:LOCALAPPDATA "gpum"
}

$TargetJar = Join-Path $InstallDir "gpu-mgr.jar"
$TargetCmd = Join-Path $InstallDir "gpum.cmd"
$TargetPs1 = Join-Path $InstallDir "gpum.ps1"

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

function Normalize-Version {
    param([string]$Version)
    if ([string]::IsNullOrWhiteSpace($Version)) {
        return ""
    }
    return $Version.Trim().TrimStart("v")
}

function Get-RemoteText {
    param([string]$Url)
    $tmp = [IO.Path]::GetTempFileName()
    try {
        & curl.exe -fsSL $Url -o $tmp
        if ($LASTEXITCODE -ne 0) {
            throw "curl failed for $Url"
        }
        return (Get-Content -LiteralPath $tmp -Raw).Trim()
    } finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

function Download-File {
    param(
        [string]$Url,
        [string]$TargetPath
    )
    $tmp = "$TargetPath.tmp"
    try {
        & curl.exe -fsSL $Url -o $tmp
        if ($LASTEXITCODE -ne 0) {
            throw "curl failed for $Url"
        }
        Move-Item -LiteralPath $tmp -Destination $TargetPath -Force
    } finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

function Get-InstalledVersion {
    if (-not (Test-Path $TargetJar)) {
        return $null
    }
    $output = & java --enable-native-access=ALL-UNNAMED -jar $TargetJar --version 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($output)) {
        return $null
    }
    $parts = ($output | Select-Object -First 1) -split "\s+"
    if ($parts.Count -lt 2) {
        return $null
    }
    return (Normalize-Version $parts[1])
}

function Ensure-Path {
    if ($env:GPUM_SKIP_PATH_UPDATE -eq "1") {
        Write-Host "Skipping PATH update because GPUM_SKIP_PATH_UPDATE=1"
        return
    }

    $installDir = [IO.Path]::GetFullPath($InstallDir)
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $entries = @()
    if (-not [string]::IsNullOrWhiteSpace($userPath)) {
        $entries = $userPath -split ";" | Where-Object { $_ -and $_.Trim() }
    }

    foreach ($entry in $entries) {
        if ($entry.TrimEnd("\") -ieq $installDir.TrimEnd("\")) {
            Write-Host "PATH already contains $installDir"
            return
        }
    }

    $newPath = if ([string]::IsNullOrWhiteSpace($userPath)) {
        $installDir
    } else {
        "$userPath;$installDir"
    }

    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")

    $signature = @"
[System.Runtime.InteropServices.DllImport("user32.dll", SetLastError=true, CharSet=System.Runtime.InteropServices.CharSet.Auto)]
public static extern System.IntPtr SendMessageTimeout(
    System.IntPtr hWnd,
    uint Msg,
    System.UIntPtr wParam,
    string lParam,
    uint fuFlags,
    uint uTimeout,
    out System.UIntPtr lpdwResult
);
"@
    $type = Add-Type -MemberDefinition $signature -Name Win32SendMessageTimeout -Namespace GPUM -PassThru
    [UIntPtr]$result = [UIntPtr]::Zero
    $null = $type::SendMessageTimeout([IntPtr]0xffff, 0x1A, [UIntPtr]::Zero, "Environment", 2, 5000, [ref]$result)
    Write-Host "Added $installDir to the user PATH"
}

Require-Command "java"
Require-Command "powershell"
Require-Command "curl.exe"

New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null

$remoteVersion = Normalize-Version (Get-RemoteText $ReleaseVersionUrl)
if ([string]::IsNullOrWhiteSpace($remoteVersion)) {
    throw "Remote version string is empty: $ReleaseVersionUrl"
}

$localVersion = Get-InstalledVersion
if ($localVersion) {
    if ([Version]$localVersion -ge [Version]$remoteVersion) {
        Write-Host "gpum $localVersion is already installed at $TargetJar"
        Write-Host "Remote release $remoteVersion is not newer. Skipping download."
        Download-File -Url $ReleaseCmdUrl -TargetPath $TargetCmd
        Download-File -Url $ReleasePs1Url -TargetPath $TargetPs1
        Ensure-Path
        exit 0
    }

    $answer = Read-Host "Installed version $localVersion is older than remote version $remoteVersion. Upgrade? [y/N]"
    if ($answer -notin @("y", "Y", "yes", "YES")) {
        Write-Host "Cancelled."
        exit 0
    }
}

Write-Host "Downloading gpum $remoteVersion ..."
Download-File -Url $ReleaseJarUrl -TargetPath $TargetJar
Download-File -Url $ReleaseCmdUrl -TargetPath $TargetCmd
Download-File -Url $ReleasePs1Url -TargetPath $TargetPs1
Ensure-Path

$env:PATH = "$InstallDir;$env:PATH"
Write-Host "Installed gpum $remoteVersion"
Write-Host "Launcher: $TargetCmd"
Write-Host "Jar: $TargetJar"
Write-Host "Run: gpum --help"
