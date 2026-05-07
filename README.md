# gpum

GPU inventory, allocation, audit, and operations CLI for AI training and inference servers.

> [!NOTICE]
> `gpum` is designed to be useful even without expensive hardware. The project includes detector fixture tests, mixed-fleet integration tests, and SQLite-backed command tests so most workflows can be validated on a normal development machine.

> [!WARNING]
> Hardware discovery quality depends on vendor tooling installed on the target host. For full metrics, install the vendor CLI for the GPUs you expect to manage:
> - NVIDIA: `nvidia-smi`
> - AMD: `amd-smi` or `rocm-smi`
> - Intel: `xpu-smi`
>
> On Windows, `gpum` also includes a PowerShell fallback for Intel Arc / Arc Pro / Flex / Max detection when `xpu-smi` is missing.

> [!TIP]
> Placeholder examples such as `<host>` or `<addr>` in the docs are not meant to be typed literally in `cmd.exe`. Replace them with actual values.

## Table of Contents

- [English](#english)
  - [Overview](#en-overview)
  - [Current Scope](#en-current-scope)
  - [Installation](#en-installation)
  - [Quick Start](#en-quick-start)
  - [Command Groups](#en-command-groups)
  - [Supported GPU Families](#en-supported-gpu-families)
  - [Configuration](#en-configuration)
  - [SQLite, Audit, and Logs](#en-sqlite-audit-and-logs)
  - [Testing Without GPUs](#en-testing-without-gpus)
  - [Known Limits](#en-known-limits)
- [한국어](#korean)
  - [개요](#ko-overview)
  - [현재 범위](#ko-current-scope)
  - [설치](#ko-installation)
  - [빠른 시작](#ko-quick-start)
  - [명령 그룹](#ko-command-groups)
  - [지원 GPU 계열](#ko-supported-gpu-families)
  - [설정](#ko-configuration)
  - [SQLite, Audit, Log](#ko-sqlite-audit-and-logs)
  - [실장비 없이 테스트](#ko-testing-without-gpus)
  - [현재 한계](#ko-known-limits)

---

<a id="english"></a>
## English

<a id="en-overview"></a>
### Overview

`gpum` manages GPU nodes as a single operational surface.

It provides:

- multi-vendor GPU inventory detection
- node and GPU visibility
- SQLite-backed metadata persistence
- allocation lifecycle commands
- audit trail and operational logs
- governance primitives such as queue, quota, and logical partition records
- integration entrypoints for Kubernetes, MLflow, BentoML, and custom tools

The codebase is structured around:

- `cli`: Picocli command surface
- `core`: domain models, services, repository interfaces, config
- `infra/detector`: vendor-specific hardware detection
- `infra/persistence`: SQLite repositories
- `infra/executor`: local and SSH command execution

<a id="en-current-scope"></a>
### Current Scope

Working and validated:

- local node scan
- remote node registration and SSH scan flow
- NVIDIA / AMD / Intel detection
- Windows Intel Arc fallback detection through PowerShell
- node list / info / top / maintenance / label / drain / undrain
- gpu list / stats / topology / health
- allocation request / dry-run / list / info / extend / release / move / reap
- queue / quota / partition record / usage report / billing simulation
- SQLite audit and operational logs
- Windows and Linux launchers

Implemented conservatively:

- `gpu set`
- `gpu reset`

These commands validate input, check inventory, and record the request safely, but they do not force vendor-level hardware mutation yet.

<a id="en-installation"></a>
### Installation

#### Windows installer

Use [install.cmd](C:/Users/love7/Pictures/GPUManager/install.cmd:1).

What it does:

- downloads `gpu-mgr.jar` from the latest GitHub Release
- compares installed and remote versions
- skips download if the installed version is the same or newer
- asks before upgrading when the installed version is older
- writes `gpum.cmd`
- adds the install directory to the user `PATH`

It updates the user `PATH` through the registry and PowerShell, not `setx`, so it avoids the common Windows `1024` truncation warning.

Default install directory:

```text
%LocalAppData%\gpum
```

#### Linux installer

Use [install-gpum.sh](C:/Users/love7/Pictures/GPUManager/install-gpum.sh:1).

Example:

```sh
curl -fsSL https://raw.githubusercontent.com/drewdrew0414/AIGPUManager/main/install-gpum.sh | sh
```

What it does:

- downloads `gpu-mgr.jar` from the latest GitHub Release
- compares installed and remote versions
- skips download if the installed version is the same or newer
- asks before upgrading when the installed version is older
- writes `gpum`
- adds the install directory to shell `PATH` in the current shell profile if needed

Default install directory:

```text
$HOME/.local/bin
```

#### Portable launchers

If you do not want a system install, keep the launcher and `gpu-mgr.jar` in the same directory:

- Windows: [gpum.cmd](C:/Users/love7/Pictures/GPUManager/gpum.cmd:1)
- PowerShell: [gpum.ps1](C:/Users/love7/Pictures/GPUManager/gpum.ps1:1)
- Linux/macOS: [gpum](C:/Users/love7/Pictures/GPUManager/gpum:1)

Distribution bundle:

- [build/gpum-dist](C:/Users/love7/Pictures/GPUManager/build/gpum-dist)

<a id="en-quick-start"></a>
### Quick Start

Scan local inventory:

```bash
gpum node scan
gpum node list
gpum node info
```

List GPUs:

```bash
gpum gpu list
gpum gpu stats --json
gpum gpu health --check-ecc --report
```

Request allocation:

```bash
gpum alloc request --gpus 1 --vram 60000 --hours 4 --dry-run
gpum alloc request --gpus 1 --vram 60000 --hours 4
gpum alloc list
```

Remote node registration:

```bash
gpum node remote add --ip <remote-ip> --ssh-user <ssh-user> --alias <node-alias>
gpum node remote list
gpum node scan --ip <remote-ip> --ssh-user <ssh-user>
```

Windows examples:

```bat
gpum node info <host>
gpum node scan --ip <remote-ip> --ssh-user <ssh-user>
gpum node remote list
```

<a id="en-command-groups"></a>
### Command Groups

#### `node`

- `gpum node scan`
- `gpum node scan --all`
- `gpum node scan --ip <remote-ip> --ssh-user <ssh-user>`
- `gpum node list`
- `gpum node info`
- `gpum node info <host>`
- `gpum node top --metric util`
- `gpum node drain`
- `gpum node undrain`
- `gpum node maintenance --on --reason patching`
- `gpum node label --show`
- `gpum node label --set role=trainer,zone=lab`
- `gpum node remote add --ip <remote-ip> --ssh-user <ssh-user>`
- `gpum node remote list`
- `gpum node remote remove --ip <remote-ip>`

`node drain`, `node undrain`, `node maintenance`, and `node label` now default to the local host if `HOST` is omitted.

#### `gpu`

- `gpum gpu list`
- `gpum gpu list --capability mig --min-vram 80000`
- `gpum gpu stats --json`
- `gpum gpu stats --export csv`
- `gpum gpu stats --export influxdb`
- `gpum gpu health --check-ecc --thermal-test --memory-test --report`
- `gpum gpu topology --visualize`
- `gpum gpu set --id <gpu-id> --power-limit 250`
- `gpum gpu reset --id <gpu-id> --soft`

#### `alloc`

- `gpum alloc request`
- `gpum alloc request --dry-run`
- `gpum alloc list`
- `gpum alloc info --id <allocation-id>`
- `gpum alloc extend --id <allocation-id> --hours 2`
- `gpum alloc release --id <allocation-id>`
- `gpum alloc move --id <allocation-id> --to-node <node>`
- `gpum alloc reap`

#### `part`

- `gpum part create --gpu <node>:<gpu-id> --profile <profile> --count 1`
- `gpum part list`
- `gpum part destroy --id <partition-id>`
- `gpum part auto-optimize`

#### `queue`

- `gpum queue list --full --estimate`
- `gpum queue promote --id <queue-id> --val 2`
- `gpum queue demote --id <queue-id> --val 1`

#### `quota`

- `gpum quota set --name <user-or-tenant> --max-gpus 4 --max-vram 320000 --max-lease-hours 72`
- `gpum quota status --user <user> --remaining`
- `gpum quota alert --name <user-or-tenant> --threshold 80,90`

#### `audit`

- `gpum audit list --tail 20`
- `gpum audit trace <resource-id>`

#### `log`

- `gpum log write --level info --component system --category startup --message "boot ok"`
- `gpum log list --sort desc --limit 20`
- `gpum log tail --lines 20`

#### `report`

- `gpum report usage --format json --by model`
- `gpum report billing --rate-card <rate-card-file>`

#### `integration`

- `gpum integration k8s contexts`
- `gpum integration k8s pods`
- `gpum integration mlflow status`
- `gpum integration mlflow runs`
- `gpum integration bentoml list`
- `gpum integration tool --name custom`

#### `system`

- `gpum system config --show-defaults`
- `gpum system config --edit`
- `gpum system db-check --repair --vacuum --orphan-clean`
- `gpum system health`
- `gpum system backup --path <backup-file>`
- `gpum system restore --path <backup-file>`
- `gpum system update`

<a id="en-supported-gpu-families"></a>
### Supported GPU Families

Representative test coverage exists for:

- NVIDIA: `H100`, `H200`, `B200`, `A100`, `RTX 4090`, `RTX 6000 Ada`, `L40S`
- AMD: `MI210`, `MI250X`, `MI300X`, `Radeon PRO W7900`
- Intel: `Data Center GPU Max 1100`, `Max 1550`, `Arc Pro A60`, `Flex 170`

Windows fallback coverage also includes Intel Arc family detection through the display adapter model path.

<a id="en-configuration"></a>
### Configuration

Example config:

- [gpum.example.yaml](C:/Users/love7/Pictures/GPUManager/gpum.example.yaml:1)

Use:

```bash
gpum --config gpum.example.yaml system config
```

Configurable areas:

- `tools`
  - `nvidiaSmi`
  - `amdSmi`
  - `rocmSmi`
  - `xpuSmi`
  - `ssh`
  - `kubectl`
  - `mlflow`
  - `bentoml`
  - `powershell`
  - `cmd`
  - `bash`
- `kubernetes`
- `mlflow`
- `bentoml`
- `externalTools`

<a id="en-sqlite-audit-and-logs"></a>
### SQLite, Audit, and Logs

Default database path:

```text
data/gpu-mgr.db
```

Stored data:

- nodes
- GPUs
- node attributes and labels
- remote nodes
- allocations and claims
- queue entries
- partition records
- quota policies
- audit events
- operational logs

Examples:

```bash
gpum audit list --event ALLOC_CREATE --sort desc --tail 20
gpum log list --component alloc --contains queued --sort desc --limit 50
gpum system db-check --repair --vacuum --orphan-clean
```

<a id="en-testing-without-gpus"></a>
### Testing Without GPUs

Run:

```bash
./gradlew test
```

Validation strategy:

- detector fixture parsing
- mixed-vendor fleet fixtures
- CLI command matrix tests
- SQLite repository tests
- allocation / governance flow tests

<a id="en-known-limits"></a>
### Known Limits

Still conservative or partial:

- vendor-level GPU power / clock / ECC mutation
- true process cleanup on GPU release
- real MIG partition lifecycle
- cluster-grade preemption and queue scheduling
- deep NIC / NUMA / RDMA inspection on every platform
- Intel Windows fallback metrics beyond coarse inventory

---

<a id="korean"></a>
## 한국어

<a id="ko-overview"></a>
### 개요

`gpum`은 AI 학습/추론 서버를 대상으로 GPU 인벤토리 수집, 자원 할당, 감사 로그, 운영 로그, 외부 플랫폼 연동을 제공하는 CLI입니다.

주요 역할:

- 멀티벤더 GPU 탐지
- 노드/장치 현황 조회
- SQLite 기반 메타데이터 저장
- allocation 라이프사이클 관리
- audit / operational log 관리
- queue / quota / partition record 관리
- Kubernetes / MLflow / BentoML / custom tool 연동

<a id="ko-current-scope"></a>
### 현재 범위

실제로 동작하는 범위:

- 로컬 노드 스캔
- 원격 노드 등록 및 SSH 스캔 흐름
- NVIDIA / AMD / Intel 탐지
- Windows에서 Intel Arc / Arc Pro / Flex / Max fallback 탐지
- node list / info / top / maintenance / label / drain / undrain
- gpu list / stats / topology / health
- alloc request / dry-run / list / info / extend / release / move / reap
- queue / quota / partition record / usage report / billing simulation
- SQLite audit / log
- Windows / Linux 런처

보수적으로 구현된 범위:

- `gpu set`
- `gpu reset`

이 둘은 입력 검증, 인벤토리 확인, 로그 기록까지는 하지만 실제 벤더 수준 하드웨어 제어는 아직 강제하지 않습니다.

<a id="ko-installation"></a>
### 설치

#### Windows 설치

[install.cmd](C:/Users/love7/Pictures/GPUManager/install.cmd:1)를 사용합니다.

동작:

- 최신 GitHub Release의 `gpu-mgr.jar` 다운로드
- 설치 버전과 원격 버전 비교
- 설치 버전이 같거나 높으면 다운로드 생략
- 설치 버전이 낮으면 업그레이드 여부 확인
- `gpum.cmd` 생성
- 사용자 `PATH`에 설치 디렉터리 자동 추가

중요한 점:

- `setx`를 쓰지 않습니다.
- PowerShell과 레지스트리 기반으로 user `PATH`를 갱신하므로 `1024` 길이 경고 문제를 피합니다.

기본 설치 경로:

```text
%LocalAppData%\gpum
```

#### Linux 설치

[install-gpum.sh](C:/Users/love7/Pictures/GPUManager/install-gpum.sh:1)를 사용합니다.

예시:

```sh
curl -fsSL https://raw.githubusercontent.com/drewdrew0414/AIGPUManager/main/install-gpum.sh | sh
```

동작:

- 최신 GitHub Release의 `gpu-mgr.jar` 다운로드
- 설치 버전과 원격 버전 비교
- 설치 버전이 같거나 높으면 생략
- 설치 버전이 낮으면 업그레이드 여부 확인
- `gpum` 런처 생성
- 필요 시 shell profile에 `PATH` 추가

기본 설치 경로:

```text
$HOME/.local/bin
```

#### 포터블 실행

설치 대신 런처와 `gpu-mgr.jar`를 같은 디렉터리에 두고 바로 실행할 수도 있습니다.

- Windows: [gpum.cmd](C:/Users/love7/Pictures/GPUManager/gpum.cmd:1)
- PowerShell: [gpum.ps1](C:/Users/love7/Pictures/GPUManager/gpum.ps1:1)
- Linux/macOS: [gpum](C:/Users/love7/Pictures/GPUManager/gpum:1)

배포 번들:

- [build/gpum-dist](C:/Users/love7/Pictures/GPUManager/build/gpum-dist)

<a id="ko-quick-start"></a>
### 빠른 시작

로컬 인벤토리 스캔:

```bash
gpum node scan
gpum node list
gpum node info
```

GPU 조회:

```bash
gpum gpu list
gpum gpu stats --json
gpum gpu health --check-ecc --report
```

할당 요청:

```bash
gpum alloc request --gpus 1 --vram 60000 --hours 4 --dry-run
gpum alloc request --gpus 1 --vram 60000 --hours 4
gpum alloc list
```

원격 노드 등록:

```bash
gpum node remote add --ip <remote-ip> --ssh-user <ssh-user> --alias <node-alias>
gpum node remote list
gpum node scan --ip <remote-ip> --ssh-user <ssh-user>
```

Windows에서 실제 입력 예시:

```bat
gpum node info <host>
gpum node scan --ip <remote-ip> --ssh-user <ssh-user>
gpum node remote list
```

`<host>`, `<addr>` 같은 문구를 그대로 치면 안 됩니다. 실제 값으로 바꿔서 실행해야 합니다.

<a id="ko-command-groups"></a>
### 명령 그룹

#### `node`

- `gpum node scan`
- `gpum node scan --all`
- `gpum node scan --ip <remote-ip> --ssh-user <ssh-user>`
- `gpum node list`
- `gpum node info`
- `gpum node info <host>`
- `gpum node top --metric util`
- `gpum node drain`
- `gpum node undrain`
- `gpum node maintenance --on --reason patching`
- `gpum node label --show`
- `gpum node label --set role=trainer,zone=lab`
- `gpum node remote add --ip <remote-ip> --ssh-user <ssh-user>`
- `gpum node remote list`
- `gpum node remote remove --ip <remote-ip>`

`node drain`, `node undrain`, `node maintenance`, `node label`은 `HOST`를 생략하면 로컬 호스트를 기본으로 사용합니다.

#### `gpu`

- `gpum gpu list`
- `gpum gpu list --capability mig --min-vram 80000`
- `gpum gpu stats --json`
- `gpum gpu stats --export csv`
- `gpum gpu stats --export influxdb`
- `gpum gpu health --check-ecc --thermal-test --memory-test --report`
- `gpum gpu topology --visualize`
- `gpum gpu set --id <gpu-id> --power-limit 250`
- `gpum gpu reset --id <gpu-id> --soft`

#### `alloc`

- `gpum alloc request`
- `gpum alloc request --dry-run`
- `gpum alloc list`
- `gpum alloc info --id <allocation-id>`
- `gpum alloc extend --id <allocation-id> --hours 2`
- `gpum alloc release --id <allocation-id>`
- `gpum alloc move --id <allocation-id> --to-node <node>`
- `gpum alloc reap`

#### `part`

- `gpum part create --gpu <node>:<gpu-id> --profile <profile> --count 1`
- `gpum part list`
- `gpum part destroy --id <partition-id>`
- `gpum part auto-optimize`

#### `queue`

- `gpum queue list --full --estimate`
- `gpum queue promote --id <queue-id> --val 2`
- `gpum queue demote --id <queue-id> --val 1`

#### `quota`

- `gpum quota set --name <user-or-tenant> --max-gpus 4 --max-vram 320000 --max-lease-hours 72`
- `gpum quota status --user <user> --remaining`
- `gpum quota alert --name <user-or-tenant> --threshold 80,90`

#### `audit`

- `gpum audit list --tail 20`
- `gpum audit trace <resource-id>`

#### `log`

- `gpum log write --level info --component system --category startup --message "boot ok"`
- `gpum log list --sort desc --limit 20`
- `gpum log tail --lines 20`

#### `report`

- `gpum report usage --format json --by model`
- `gpum report billing --rate-card <rate-card-file>`

#### `integration`

- `gpum integration k8s contexts`
- `gpum integration k8s pods`
- `gpum integration mlflow status`
- `gpum integration mlflow runs`
- `gpum integration bentoml list`
- `gpum integration tool --name custom`

#### `system`

- `gpum system config --show-defaults`
- `gpum system config --edit`
- `gpum system db-check --repair --vacuum --orphan-clean`
- `gpum system health`
- `gpum system backup --path <backup-file>`
- `gpum system restore --path <backup-file>`
- `gpum system update`

<a id="ko-supported-gpu-families"></a>
### 지원 GPU 계열

대표 테스트 커버리지:

- NVIDIA: `H100`, `H200`, `B200`, `A100`, `RTX 4090`, `RTX 6000 Ada`, `L40S`
- AMD: `MI210`, `MI250X`, `MI300X`, `Radeon PRO W7900`
- Intel: `Data Center GPU Max 1100`, `Max 1550`, `Arc Pro A60`, `Flex 170`

Windows fallback 경로로 Intel Arc 계열도 인벤토리 탐지가 가능하도록 보강되어 있습니다.

<a id="ko-configuration"></a>
### 설정

예제 파일:

- [gpum.example.yaml](C:/Users/love7/Pictures/GPUManager/gpum.example.yaml:1)

사용:

```bash
gpum --config gpum.example.yaml system config
```

설정 가능 영역:

- `tools`
  - `nvidiaSmi`
  - `amdSmi`
  - `rocmSmi`
  - `xpuSmi`
  - `ssh`
  - `kubectl`
  - `mlflow`
  - `bentoml`
  - `powershell`
  - `cmd`
  - `bash`
- `kubernetes`
- `mlflow`
- `bentoml`
- `externalTools`

<a id="ko-sqlite-audit-and-logs"></a>
### SQLite, Audit, Log

기본 DB 경로:

```text
data/gpu-mgr.db
```

저장 데이터:

- 노드
- GPU
- 노드 속성/라벨
- 원격 노드
- allocation / claim
- queue entry
- partition record
- quota policy
- audit event
- operational log

예시:

```bash
gpum audit list --event ALLOC_CREATE --sort desc --tail 20
gpum log list --component alloc --contains queued --sort desc --limit 50
gpum system db-check --repair --vacuum --orphan-clean
```

<a id="ko-testing-without-gpus"></a>
### 실장비 없이 테스트

실행:

```bash
./gradlew test
```

검증 방식:

- detector fixture parsing
- mixed-vendor fleet fixture
- CLI command matrix test
- SQLite repository test
- allocation / governance flow test

<a id="ko-known-limits"></a>
### 현재 한계

아직 보수적이거나 부분 구현인 영역:

- 벤더 수준 GPU power / clock / ECC 실제 제어
- release 시 실제 process cleanup
- 진짜 MIG lifecycle 제어
- 대규모 queue scheduling / preemption
- 모든 플랫폼에서의 깊은 NIC / NUMA / RDMA 분석
- Intel Windows fallback의 정밀 메트릭 수집
