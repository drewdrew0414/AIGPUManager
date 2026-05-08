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
  - [Safety Interlocks](#en-safety-interlocks)
  - [Installation](#en-installation)
  - [Quick Start](#en-quick-start)
  - [Command Groups](#en-command-groups)
  - [Runtime Layer](#en-runtime-layer)
  - [AI Tooling Integration](#en-ai-tooling-integration)
  - [Supported GPU Families](#en-supported-gpu-families)
  - [Configuration](#en-configuration)
  - [SQLite, Audit, and Logs](#en-sqlite-audit-and-logs)
  - [Testing Without GPUs](#en-testing-without-gpus)
  - [Practical Gaps](#en-practical-gaps)
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
- guarded hardware write paths for selected `gpu set` / `gpu reset` operations
- allocation-scoped AI launcher environment for `python`, `torchrun`, `accelerate`, `deepspeed`, and `vllm`
- scan rate limiting to reduce subprocess flooding
- topology-aware packed/spread placement using NVLink, XGMI, and Xe Link hints
- health scoring and scheduler quarantine flags
- Prometheus text export for inventory, allocation, and health metrics
- heuristic VRAM estimation for AI workload requests
- optional direct NVML / Level Zero telemetry through JNA when native libraries are installed
- SQLite-backed runtime worker registry, restart budget, recycle preview/execute, OOM recovery strategy hooks
- Docker and Kubernetes reconcile checks for allocation visibility drift
- checkpoint/restore migration planning for workers that provide explicit commands

Write support is intentionally guarded:

- `gpu set --apply`
  - NVIDIA: power limit, compute mode, ECC
  - AMD: power cap
  - Intel: power limit, ECC
- `gpu reset --apply`
  - guarded soft reset path only

Blocked on purpose:

- hard reset
- unsafe clock fixing
- integrated GPU reset
- write against non-local GPUs
- write against GPUs with active allocations, unless explicitly overridden where supported

<a id="en-safety-interlocks"></a>
### Safety Interlocks

`gpum` now treats hardware mutation as opt-in only.

Default behavior:

- `gpu set` without `--apply` is a dry-run preview
- `gpu reset` without `--apply` is a dry-run preview
- the CLI prints planned vendor commands, blockers, and required safety conditions

To allow a real hardware write:

```bash
export GPUM_ENABLE_HARDWARE_WRITE=1
gpum gpu set --id <node>:<gpu-id> --power-limit 350 --apply
```

Windows:

```bat
set GPUM_ENABLE_HARDWARE_WRITE=1
gpum gpu reset --id <node>:<gpu-id> --soft --apply
```

Additional guards:

- local node only
- allocation ownership conflict check
- reboot-required guard for ECC changes
- linked-fabric reset guard
- vendor min/max power limit revalidation at apply time
- remote apply is allowed only through a recorded SSH target and remote `gpum` agent path
- high-risk `gpu set --apply`, `gpu reset --apply`, `part create --apply`, and `part destroy --apply` create approval requests unless the actor already has the required approval role
- `alloc release --kill-process` performs real local process cleanup with PID safety checks and same-user preference unless forced
- MIG apply is limited to NVIDIA devices that already advertise MIG capability; automatic mode enable is still intentionally blocked
- runtime worker commands only manage workers registered in `gpum`; migration execution requires explicit checkpoint and restore commands
- Docker/K8s reconcile commands are read-only and report drift instead of mutating containers or pods

Approval flow example:

```bash
gpum gpu reset --id <node>:<gpu-id> --soft --apply
gpum rbac approval list --status pending
gpum rbac approval approve --id <approval-id> --reason "change window approved"
gpum gpu reset --id <node>:<gpu-id> --soft --apply --approval-id <approval-id>
```

Remote agent apply example:

```bash
gpum gpu set --id <remote-node>:<gpu-id> --power-limit 320 --via-agent --apply --approval-id <approval-id>
```

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
- `gpum gpu health --score`
- `gpum gpu health --score --quarantine-threshold 40`
- `gpum gpu topology --visualize`
- `gpum gpu set --id <gpu-id> --power-limit 250`
- `gpum gpu reset --id <gpu-id> --soft`
- `gpum gpu set --id <node>:<gpu-id> --power-limit 300`
- `gpum gpu set --id <node>:<gpu-id> --power-limit 300 --apply`
- `gpum gpu set --id <node>:<gpu-id> --ecc on --allow-reboot-required`
- `gpum gpu reset --id <node>:<gpu-id> --soft`
- `gpum gpu reset --id <node>:<gpu-id> --soft --apply`

#### `alloc`

- `gpum alloc request`
- `gpum alloc request --dry-run`
- `gpum alloc list`
- `gpum alloc info --id <allocation-id>`
- `gpum alloc extend --id <allocation-id> --hours 2`
- `gpum alloc release --id <allocation-id>`
- `gpum alloc move --id <allocation-id> --to-node <node>`
- `gpum alloc reap`
- `gpum alloc estimate --model <model-name> --params-b <n> --precision fp16 --context <tokens> --batch <n>`

#### `part`

- `gpum part create --gpu <node>:<gpu-id> --profile <profile> --count 1`
- `gpum part create --gpu <node>:<gpu-id> --profile <profile> --count 1 --apply --approval-id <approval-id>`
- `gpum part list`
- `gpum part destroy --id <partition-id>`
- `gpum part destroy --id <partition-id> --apply --approval-id <approval-id>`
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
- `gpum report prometheus`
- `gpum report prometheus --path <metrics-file>`

#### `integration`

- `gpum integration k8s contexts`
- `gpum integration k8s pods`
- `gpum integration k8s submit --name <job-name> --image <image> --allocation-id <allocation-id>`
- `gpum integration k8s submit --name <job-name> --image <image> --allocation-id <allocation-id> --kind <Job|CronJob|Deployment>`
- `gpum integration k8s submit --name <job-name> --template <template-file> --allocation-id <allocation-id>`
- `gpum integration k8s submit --name <job-name> --image <image> --dataset-pvc <claim> --secret-env API_KEY=<secret>:key`
- `gpum integration k8s submit --name <job-name> --image <image> --allocation-id <allocation-id> --execute`
- `gpum integration mlflow status`
- `gpum integration mlflow runs`
- `gpum integration bentoml list`
- `gpum integration ai env --allocation-id <allocation-id>`
- `gpum integration ai env --allocation-id <allocation-id> --format json`
- `gpum integration ai launch --allocation-id <allocation-id> --tool torchrun --from-file <template-file>`
- `gpum integration ai launch --allocation-id <allocation-id> --tool torchrun --via-ssh --ssh-user <ssh-user>`
- `gpum integration ai preset list`
- `gpum integration ai preset render --allocation-id <allocation-id> --name torchrun-ddp --entrypoint train.py`
- `gpum integration ai preset render --allocation-id <allocation-id> --name slurm-sbatch --entrypoint train.py`
- `gpum integration ai preset render --allocation-id <allocation-id> --name ray-job --entrypoint train.py`
- `gpum integration ai launch --allocation-id <allocation-id> --tool torchrun --arg --nnodes --arg 1 --arg train.py`
- `gpum integration ai launch --allocation-id <allocation-id> --tool accelerate --arg launch --arg train.py --execute`
- `gpum integration tool --name custom`

<a id="en-runtime-layer"></a>
### Runtime Layer

`gpum runtime` adds the worker/runtime layer needed for safer AI workload operations without requiring expensive hardware in development.

Native telemetry:

- `gpum runtime native metrics`
- Uses NVML for NVIDIA when `nvml` is available.
- Uses Level Zero discovery for Intel when `ze_loader` is available.
- Falls back to clear `unavailable` rows instead of failing the command when native libraries are missing.

Worker lifecycle:

- `gpum runtime worker register --id <worker-id> --allocation-id <allocation-id> --command "python train.py"`
- `gpum runtime worker register --id <worker-id> --allocation-id <allocation-id> --command "python train.py" --oom-recovery-command "python defrag.py"`
- `gpum runtime worker list`
- `gpum runtime worker start --id <worker-id>`
- `gpum runtime worker stop --id <worker-id>`
- `gpum runtime worker stop --id <worker-id> --force`
- `gpum runtime worker restart --id <worker-id> --reason oom`
- `gpum runtime worker recycle`
- `gpum runtime worker recycle --execute`
- `gpum runtime worker events --id <worker-id>`
- `gpum runtime daemon run --once`
- `gpum runtime daemon run --interval-sec 30 --execute`

OOM recovery:

- `gpum runtime oom handle --allocation-id <allocation-id> --strategy restart`
- `gpum runtime oom handle --allocation-id <allocation-id> --strategy defrag --execute`
- `gpum runtime oom handle --allocation-id <allocation-id> --strategy stop --execute`
- `gpum runtime oom handle --allocation-id <allocation-id> --strategy release --execute`

Container/orchestrator reconcile:

- `gpum runtime reconcile docker`
- `gpum runtime reconcile k8s`

Checkpoint migration:

- `gpum runtime worker register --id <worker-id> --command "python train.py" --checkpoint-command "python save_ckpt.py" --restore-command "python restore_ckpt.py"`
- `gpum runtime migrate plan --worker-id <worker-id> --to-node <node>`
- `gpum runtime migrate plan --worker-id <worker-id> --to-node <node> --execute`

> [!WARNING]
> `gpum` does not pretend that live GPU memory migration is universally possible. The executable migration path is checkpoint/restore based and only runs the commands you explicitly registered.

<a id="en-ai-tooling-integration"></a>
### AI Tooling Integration

`gpum` can now bridge allocation state into common AI runtimes.

Supported allocation-scoped launch targets:

- `python`
- `torchrun`
- `accelerate`
- `deepspeed`
- `vllm`

Preset generators:

- `torchrun-ddp`
- `accelerate`
- `deepspeed`
- `vllm-serve`
- `slurm-sbatch`
- `ray-job`

What it injects automatically:

- `GPUM_ALLOCATION_ID`
- `GPUM_PRIMARY_NODE`
- `GPUM_GPU_COUNT`
- `GPUM_GPU_MODELS`
- `GPUM_GPU_UUIDS`
- vendor-specific visibility variables
  - NVIDIA: `CUDA_VISIBLE_DEVICES`, `NVIDIA_VISIBLE_DEVICES`
  - AMD: `ROCR_VISIBLE_DEVICES`, `HIP_VISIBLE_DEVICES`
  - Intel: `ZE_AFFINITY_MASK`, `ONEAPI_DEVICE_SELECTOR`
- MLflow environment when configured
  - `MLFLOW_TRACKING_URI`
  - `MLFLOW_REGISTRY_URI`
  - `MLFLOW_EXPERIMENT_NAME`

This allows practical handoff from `alloc` to training or inference processes without manually reconstructing device visibility.

Distributed launch behavior:

- multi-node allocations now export:
  - `GPUM_NODE_HOSTS`
  - `GPUM_NODE_COUNT`
  - `MASTER_ADDR`
  - `GPUM_RDZV_ENDPOINT`
- preset renderers use these values to produce torchrun / DeepSpeed / Accelerate launch arguments
- `--via-ssh` launches the wrapped AI command on the allocation primary node through the configured `ssh` client

Argument template support:

- `--from-file <template-file>`
- one rendered argument per line
- blank lines and `#` comments are ignored
- placeholders use `{{NAME}}`

Typical placeholders:

- `{{GPUM_ALLOCATION_ID}}`
- `{{GPUM_PRIMARY_NODE}}`
- `{{GPUM_GPU_COUNT}}`
- `{{CUDA_VISIBLE_DEVICES}}`
- `{{ZE_AFFINITY_MASK}}`

Example template:

```text
--nproc-per-node
{{GPUM_GPU_COUNT}}
train.py
--allocation-id
{{GPUM_ALLOCATION_ID}}
```

Kubernetes submit behavior:

- default mode prints the patched Job manifest
- `--execute` applies it through `kubectl apply`
- `--kind` supports `Job`, `CronJob`, and `Deployment`
- `--template <template-file>` uses a user-provided YAML template before patching
- when `--allocation-id` is present, `gpum` injects:
  - allocation environment variables
  - GPU limits and requests
  - `gpum_allocation` label
  - `kubernetes.io/hostname` pin to the allocation primary node
- `--dataset-pvc` and `--mount-pvc` inject persistent volume mounts
- `--secret-env` injects `secretKeyRef` environment variables
- `--watch-sec`, `--retry`, and `--rollback-on-fail` provide conservative rollout control

For non-NVIDIA clusters, set `kubernetes.gpuResourceKey` in config explicitly.

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
  - `docker`
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

<a id="en-practical-gaps"></a>
### Practical Gaps

Important production features still missing or partial:

- allocator awareness of NUMA, NIC locality, and storage bandwidth
- fair-share queue aging and true preemption
- deeper scheduler integration with Slurm and Ray cluster state
- richer preset coverage for ZeRO/offload/topology-specific launch tuning
- GPU burn-in / memory test tooling beyond snapshot health heuristics
- power/thermal policy profiles per tenant or workload class
- remote hardware writes still depend on SSH reachability and a remote `gpum` install; a signed persistent daemon channel is still not included
- MIG mode enable/disable is still intentionally manual; `gpum` only applies create/destroy within an already MIG-capable path
- AMD and Intel partition lifecycle remains logical-only; hardware-backed partition apply currently targets NVIDIA MIG
- process cleanup is local-node only and depends on vendor CLI process reporting
- NVML/Level Zero support is intentionally minimal: it proves native access and basic NVIDIA metrics, while advanced Intel utilization counters still need deeper Level Zero metric streamer work
- OOM recovery is policy-driven and worker-scoped; framework-level tensor defragmentation still needs framework adapters such as PyTorch/vLLM hooks
- Docker and Kubernetes reconcile is read-only drift detection; device-plugin repair and cgroup mutation are not automatic
- migration is checkpoint/restore based; transparent hot memory migration remains out of scope

<a id="en-known-limits"></a>
### Known Limits

Still conservative or partial:

- vendor-level clock mutation is still blocked
- forceful process cleanup is local and PID-scoped, not a cluster-wide kill service
- real NVIDIA MIG apply exists only for already MIG-capable NVIDIA paths; AMD and Intel partitioning remains logical
- cluster-grade preemption and queue scheduling
- deep NIC / NUMA / RDMA inspection on every platform
- Intel Windows fallback metrics beyond coarse inventory unless native Intel tooling is installed

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
  - `docker`
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
