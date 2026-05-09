# Installation

## Requirements

- Java 21 or newer
- Included Gradle wrapper for local builds
- Default local database: SQLite at `data/gpu-mgr.db`
- Optional platform tools:
  - NVIDIA: `nvidia-smi`
  - AMD: `amd-smi` or `rocm-smi`
  - Intel: `xpu-smi`
  - Runtime and integrations: `docker`, `kubectl`, `mlflow`, `bentoml`, `ssh`

## Quick Install

### Windows

```powershell
iwr -UseBasicParsing https://raw.githubusercontent.com/drewdrew0414/AIGPUManager/main/install-window.ps1 | iex
```

### Linux

```sh
curl -fsSL https://raw.githubusercontent.com/drewdrew0414/AIGPUManager/main/install-gpum.sh | sh
```

### macOS

```sh
curl -fsSL https://raw.githubusercontent.com/drewdrew0414/AIGPUManager/main/install-gpum.sh | sh
```

## Build

Windows PowerShell:

```powershell
.\gradlew.bat test gpumDistZip
java --enable-native-access=ALL-UNNAMED -jar build\libs\gpu-mgr.jar --version
```

Linux/macOS:

```sh
./gradlew test gpumDistZip
java --enable-native-access=ALL-UNNAMED -jar build/libs/gpu-mgr.jar --version
```

## Distribution Artifacts

```text
build/libs/gpu-mgr.jar
build/gpum-dist/
build/distributions/gpum-dist.zip
```

Portable launcher files:

- `gpum.cmd`
- `gpum.ps1`
- `gpum`
- `gpu-mgr.jar`

