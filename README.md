# gpum

GPU inventory, allocation, audit, logging, and integration CLI for AI training infrastructure.

> [ NOTICE ]
> `gpum` is designed to work without expensive GPU hardware during development. The project includes detector parsing tests, fixture-based fleet tests, and CLI integration tests so most behavior can be validated on a normal machine.

> [ WARNING ]
> Some command groups are production-ready enough for local inventory, SQLite-backed metadata, logs, and basic allocation workflows. Other groups are still contract-first placeholders and are clearly marked below.

> [ TIP ]
> On Windows, use `gpum.cmd`. On PowerShell, `.\gpum.ps1` also works. On Unix-like systems, use `./gpum`.

## Table of Contents

- [English](#english)
  - [Overview](#overview)
  - [Status](#status)
  - [Features](#features)
  - [Supported GPU Families](#supported-gpu-families)
  - [Architecture](#architecture)
  - [Build and Launch](#build-and-launch)
  - [Windows Support](#windows-support)
  - [Configuration](#configuration)
  - [SQLite, Audit, and Logs](#sqlite-audit-and-logs)
  - [Command Reference](#command-reference)
  - [Integrations](#integrations)
  - [Testing Without GPUs](#testing-without-gpus)
  - [Known Limits](#known-limits)
- [한국어](#한국어)
  - [개요](#개요)
  - [상태](#상태)
  - [기능](#기능)
  - [지원 GPU 계열](#지원-gpu-계열)
  - [구조](#구조)
  - [빌드 및 실행](#빌드-및-실행)
  - [Windows 지원](#windows-지원)
  - [설정](#설정)
  - [SQLite, Audit, Log](#sqlite-audit-log)
  - [명령어 레퍼런스](#명령어-레퍼런스)
  - [연동](#연동)
  - [실장비 없이 테스트](#실장비-없이-테스트)
  - [현재 한계](#현재-한계)

---

## English

### Overview

`gpum` manages GPU servers for AI training and inference workloads. It normalizes NVIDIA, AMD, and Intel hardware into one inventory model, persists metadata in SQLite, exposes allocation and audit flows through a CLI, and now includes configurable integrations for Kubernetes, MLflow, BentoML, and custom external tools.

### Status

Implemented and working:

- Multi-vendor inventory detection for NVIDIA, AMD, and Intel
- Local and remote node scanning
- SQLite-backed inventory, allocations, audit events, and operational logs
- Basic allocation lifecycle: request, dry-run, list, info, extend, release, reap
- Windows-friendly local command execution fallback
- `gpum` launcher scripts: `gpum.cmd`, `gpum.ps1`, `gpum`
- Configurable tool paths and integration defaults through YAML
- Filterable and sortable `audit` and `log` queries
- Integration command surface for Kubernetes, MLflow, BentoML, and custom tools

Implemented as command contract / partial backend:

- `node drain --evict`
- `gpu set`
- `gpu reset`
- `part`
- `queue`
- `quota`
- `report`

### Features

- Unified GPU model across vendors
- Node inventory snapshots
- GPU topology and capability tracking
- Lease-based allocations
- Immutable audit trail
- Mutable operational logs with search and sort
- Remote SSH scans
- YAML-driven external tool configuration
- Windows, Linux, and macOS launch entrypoints

### Supported GPU Families

Representative coverage exists for:

- NVIDIA: `H100`, `H200`, `B200`, `A100`, `RTX 4090`, `RTX 6000 Ada`, `L40S`
- AMD: `MI210`, `MI250X`, `MI300X`, `Radeon PRO W7900`
- Intel: `Data Center GPU Max 1100`, `Max 1550`, `Arc Pro A60`, `Flex 170`

The detector architecture is not hardcoded to only these SKUs. New models are expected to flow through the same normalized `GpuDevice` model as long as their vendor tools expose compatible fields.

### Architecture

- `cli`
  - Picocli command entrypoints
- `core`
  - domain models, services, repositories, config
- `infra/detector`
  - vendor-specific hardware parsing
- `infra/persistence`
  - SQLite repositories
- `infra/executor`
  - local and SSH command execution

### Build and Launch

Build:

```bash
./gradlew shadowJar
```

Run directly:

```bash
java -jar build/libs/gpu-mgr.jar --help
```

Run with launcher:

```bash
gpum --help
```

Windows:

```powershell
.\gpum.cmd --help
.\gpum.ps1 --help
```

Unix-like:

```bash
./gpum --help
```

### Windows Support

`gpum` now includes Windows-specific improvements:

- launcher scripts for `cmd.exe` and PowerShell
- fallback executable probing for `.exe`, `.cmd`, `.bat`, `.com`
- configurable tool paths for `nvidia-smi`, `amd-smi`, `rocm-smi`, `xpu-smi`, `kubectl`, `mlflow`, `bentoml`, `ssh`

This means a Windows host can scan local inventory, query SQLite, inspect logs, and run CLI integrations without requiring a Unix shell.

### Configuration

Use `--config` with a YAML file:

```bash
gpum --config gpum.example.yaml system config
```

Example file: [gpum.example.yaml](C:/Users/love7/Pictures/GPUManager/gpum.example.yaml:1)

Supported config areas:

- `tools`
  - tool executable names or absolute paths
- `kubernetes`
  - default context, namespace, service account, image pull policy
- `mlflow`
  - tracking URI, registry URI, experiment, profile
- `bentoml`
  - endpoint, home, working directory
- `externalTools`
  - arbitrary custom commands with default arguments

Show defaults:

```bash
gpum system config --show-defaults
```

### SQLite, Audit, and Logs

SQLite is the default metadata store and works out of the box at:

```text
data/gpu-mgr.db
```

Stored entities include:

- nodes
- GPUs
- node attributes / labels
- remote node registrations
- allocations and GPU claims
- audit events
- operational log entries

Audit commands:

```bash
gpum audit list --event ALLOC_CREATE --sort desc --tail 20
gpum audit list --user alice --target alloc-123 --contains release
gpum audit trace alloc-123
```

Log commands:

```bash
gpum log write --level info --component system --category startup --message "gpum booted"
gpum log list --component alloc --contains created --sort desc --limit 50
gpum log tail --lines 20
```

### Command Reference

#### `node`

- `gpum node scan`
- `gpum node scan --all`
- `gpum node scan --ip <addr> --ssh-user <user>`
- `gpum node list`
- `gpum node info <host>`
- `gpum node top --metric <power|temp|util>`
- `gpum node drain <host>`
- `gpum node undrain <host>`
- `gpum node maintenance <host> --on|--off`
- `gpum node label <host> --set key=value`
- `gpum node remote add|list|remove`

#### `gpu`

- `gpum gpu list`
- `gpum gpu stats`
- `gpum gpu health`
- `gpum gpu topology`
- `gpum gpu set` (validation path implemented, hardware control backend pending)
- `gpum gpu reset` (validation path implemented, hardware reset backend pending)

#### `alloc`

- `gpum alloc request`
- `gpum alloc request --dry-run`
- `gpum alloc list`
- `gpum alloc info --id <id>`
- `gpum alloc extend --id <id> --hours <n>`
- `gpum alloc release --id <id>`
- `gpum alloc reap`
- `gpum alloc move` (placeholder)

#### `audit`

- `gpum audit list`
- `gpum audit trace <id>`

#### `log`

- `gpum log write`
- `gpum log list`
- `gpum log tail`

#### `integration`

- `gpum integration k8s contexts`
- `gpum integration k8s pods`
- `gpum integration k8s submit`
- `gpum integration k8s logs`
- `gpum integration mlflow status`
- `gpum integration mlflow runs`
- `gpum integration mlflow models`
- `gpum integration bentoml list`
- `gpum integration bentoml models`
- `gpum integration bentoml serve`
- `gpum integration tool --name <custom-tool>`

#### `system`

- `gpum system config`
- `gpum system db-check`
- `gpum system health`
- `gpum system backup`
- `gpum system restore`
- `gpum system update`

#### Contract-first placeholders

- `part`
- `queue`
- `quota`
- `report`

### Integrations

#### Kubernetes

Kubernetes support is CLI-driven through configured `kubectl`.

Use cases:

- inspect contexts
- inspect pods
- generate a simple GPU job manifest
- stream pod logs

#### MLflow

MLflow support is CLI-driven through configured `mlflow`.

Use cases:

- inspect effective tracking configuration
- list runs
- list registered models

#### BentoML

BentoML support is CLI-driven through configured `bentoml`.

Use cases:

- list Bentos
- list BentoML models
- start a local service

#### Custom tools

`externalTools` in YAML lets you wire additional platforms without changing code. Examples:

- Ray
- Slurm
- Airflow
- internal deployment wrappers
- cluster-specific utilities

### Testing Without GPUs

The project already supports hardware-free validation:

- detector output fixture tests
- mixed-vendor fleet fixture tests
- CLI command matrix tests
- SQLite integration tests

Run:

```bash
./gradlew test
```

### Known Limits

> [!WARNING]
> The following areas are not fully implemented yet:

- live GPU power / clock mutation
- true GPU reset and process cleanup
- full MIG lifecycle control
- queue scheduling backend
- quota enforcement backend
- billing / report generation backend
- real Kubernetes job mutation with resource patching

---

## 한국어

### 개요

`gpum`은 AI 학습/추론용 GPU 서버를 대상으로 인벤토리 수집, 자원 할당, 감사 이력, 운영 로그, 외부 플랫폼 연동을 제공하는 CLI입니다. NVIDIA, AMD, Intel 장비를 하나의 공통 모델로 정규화하고, SQLite에 메타데이터를 저장합니다.

### 상태

실제로 동작하는 범위:

- NVIDIA / AMD / Intel 인벤토리 탐지
- 로컬 / 원격 노드 스캔
- SQLite 기반 인벤토리 / allocation / audit / log 저장
- 기본 allocation 라이프사이클
- Windows 실행 경로와 `gpum` 런처
- YAML 설정
- 정렬 / 필터가 가능한 audit / log 조회
- Kubernetes / MLflow / BentoML / custom tool 연동 명령 표면

계약은 있지만 백엔드가 제한적인 범위:

- `node drain --evict`
- `gpu set`
- `gpu reset`
- `part`
- `queue`
- `quota`
- `report`

### 기능

- 멀티벤더 GPU 공통 추상화
- 노드/장치 인벤토리 수집
- GPU capability / topology 표시
- lease 기반 allocation
- immutable audit trail
- 검색 가능한 operational log
- SSH 원격 스캔
- 외부 도구 경로 / 기본값 YAML 설정

### 지원 GPU 계열

대표 테스트 커버리지:

- NVIDIA: `H100`, `H200`, `B200`, `A100`, `RTX 4090`, `RTX 6000 Ada`, `L40S`
- AMD: `MI210`, `MI250X`, `MI300X`, `Radeon PRO W7900`
- Intel: `Data Center GPU Max 1100`, `Max 1550`, `Arc Pro A60`, `Flex 170`

### 구조

- `cli`
  - 사용자 명령 진입점
- `core`
  - 도메인 모델, 서비스, 설정
- `infra/detector`
  - 벤더별 하드웨어 파서
- `infra/persistence`
  - SQLite 저장소
- `infra/executor`
  - 로컬 / SSH 명령 실행

### 빌드 및 실행

빌드:

```bash
./gradlew shadowJar
```

직접 실행:

```bash
java -jar build/libs/gpu-mgr.jar --help
```

런처 사용:

```bash
gpum --help
```

Windows:

```powershell
.\gpum.cmd --help
.\gpum.ps1 --help
```

### Windows 지원

Windows 확장 내용:

- `gpum.cmd`, `gpum.ps1`
- `.exe`, `.cmd`, `.bat`, `.com` 실행 fallback
- GPU vendor / kubectl / mlflow / bentoml / ssh 경로를 YAML로 교체 가능

### 설정

YAML 설정 파일 예시는 [gpum.example.yaml](C:/Users/love7/Pictures/GPUManager/gpum.example.yaml:1) 에 있습니다.

사용 예:

```bash
gpum --config gpum.example.yaml system config
```

설정 범위:

- `tools`
- `kubernetes`
- `mlflow`
- `bentoml`
- `externalTools`

기본값 보기:

```bash
gpum system config --show-defaults
```

### SQLite, Audit, Log

기본 DB:

```text
data/gpu-mgr.db
```

저장되는 주요 데이터:

- 노드
- GPU
- 라벨 / 속성
- 원격 노드 등록
- allocation / claim
- audit event
- operational log

Audit 예:

```bash
gpum audit list --event ALLOC_CREATE --sort desc --tail 20
gpum audit trace alloc-123
```

Log 예:

```bash
gpum log write --level info --component system --category startup --message "gpum booted"
gpum log list --component alloc --contains created --sort desc --limit 50
gpum log tail --lines 20
```

### 명령어 레퍼런스

#### `node`

- `gpum node scan`
- `gpum node scan --all`
- `gpum node scan --ip <addr> --ssh-user <user>`
- `gpum node list`
- `gpum node info <host>`
- `gpum node top`
- `gpum node drain`
- `gpum node undrain`
- `gpum node maintenance`
- `gpum node label`
- `gpum node remote add|list|remove`

#### `gpu`

- `gpum gpu list`
- `gpum gpu stats`
- `gpum gpu health`
- `gpum gpu topology`
- `gpum gpu set`
- `gpum gpu reset`

#### `alloc`

- `gpum alloc request`
- `gpum alloc request --dry-run`
- `gpum alloc list`
- `gpum alloc info`
- `gpum alloc extend`
- `gpum alloc release`
- `gpum alloc reap`
- `gpum alloc move`

#### `audit`

- `gpum audit list`
- `gpum audit trace`

#### `log`

- `gpum log write`
- `gpum log list`
- `gpum log tail`

#### `integration`

- `gpum integration k8s contexts|pods|submit|logs`
- `gpum integration mlflow status|runs|models`
- `gpum integration bentoml list|models|serve`
- `gpum integration tool --name <custom>`

#### `system`

- `gpum system config`
- `gpum system db-check`
- `gpum system health`
- `gpum system backup`
- `gpum system restore`
- `gpum system update`

#### 플레이스홀더

- `part`
- `queue`
- `quota`
- `report`

### 연동

#### Kubernetes

- context 조회
- pod 조회
- 간단한 GPU job manifest 생성
- pod log 조회

#### MLflow

- tracking 설정 확인
- run 조회
- model 조회

#### BentoML

- Bento 목록 조회
- model 목록 조회
- serve 실행

#### 기타 도구

`externalTools`를 통해 Ray, Slurm, 사내 wrapper 등도 연결할 수 있습니다.

### 실장비 없이 테스트

가능한 검증 방식:

- detector fixture test
- mixed fleet fixture test
- CLI integration test
- SQLite integration test

실행:

```bash
./gradlew test
```

### 현재 한계

> [!WARNING]
> 아직 완전하지 않은 부분:

- 실제 GPU power / clock 제어
- 진짜 GPU reset / process cleaner
- 완전한 MIG lifecycle
- queue backend
- quota enforcement
- billing / report backend
- 실제 Kubernetes 리소스 patching 기반 배포 자동화
