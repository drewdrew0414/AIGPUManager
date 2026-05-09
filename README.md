# gpum 1.1.0

`gpum` is a Java 21 based GPU operations CLI and lightweight gRPC control plane for AI training and inference servers.

`gpum`은 AI 학습 및 추론 서버를 운영하기 위한 Java 21 기반 GPU 운영 CLI 및 경량 gRPC 제어 서버입니다.

> Safety first: commands that can mutate hardware, kill processes, execute external tools, or change cluster state are preview-first. Real execution requires explicit flags such as `--execute`, `--apply`, RBAC approval, and in hardware cases `GPUM_ENABLE_HARDWARE_WRITE=1`.

> 안전 우선: 하드웨어 변경, 프로세스 종료, 외부 도구 실행, 클러스터 상태 변경은 기본적으로 미리보기입니다. 실제 실행에는 `--execute`, `--apply`, RBAC 승인, 하드웨어 변경 시 `GPUM_ENABLE_HARDWARE_WRITE=1` 같은 명시적 조건이 필요합니다.

## Table of Contents

- [한국어 가이드](#한국어-가이드)
  - [핵심 기능](#핵심-기능)
  - [설치와 빌드](#설치와-빌드)
  - [운영 흐름](#운영-흐름)
  - [안전 모델](#안전-모델)
- [English Guide](#english-guide)
  - [Core Capabilities](#core-capabilities)
  - [Install and Build](#install-and-build)
  - [Operating Flow](#operating-flow)
  - [Safety Model](#safety-model)
- [Command Reference and Examples](#command-reference-and-examples)
  - [Global Options](#global-options)
  - [Node Commands](#node-commands)
  - [GPU Commands](#gpu-commands)
  - [Allocation Commands](#allocation-commands)
  - [Compute Commands](#compute-commands)
  - [Scheduling Commands](#scheduling-commands)
  - [Data Commands](#data-commands)
  - [Job Commands](#job-commands)
  - [Partition Commands](#partition-commands)
  - [Queue Commands](#queue-commands)
  - [Quota Commands](#quota-commands)
  - [Audit and Log Commands](#audit-and-log-commands)
  - [Observability Commands](#observability-commands)
  - [Integration Commands](#integration-commands)
  - [Report Commands](#report-commands)
  - [RBAC Commands](#rbac-commands)
  - [Runtime Commands](#runtime-commands)
  - [Secret Commands](#secret-commands)
  - [Developer Commands](#developer-commands)
  - [Server Commands](#server-commands)
  - [System and Safety Commands](#system-and-safety-commands)
- [End-to-End Recipes](#end-to-end-recipes)
- [Operational Notes](#operational-notes)

## 한국어 가이드

### 핵심 기능

- NVIDIA, AMD, Intel GPU 인벤토리 수집 및 다중 벤더 표시
- GPU lease 기반 할당, 연장, 이동, 해제, 만료 회수
- MIG/partition 관리, topology-aware placement, best-fit/worst-fit placement, backfill
- CPU/RAM/PID cgroup quota, RDMA/InfiniBand 정책, NPU/TPU/LPU/FPGA accelerator registry
- Docker, Apptainer, Singularity 기반 batch job 계획 및 interactive session 계획
- Jupyter, VS Code tunnel, SSH session, runtime worker, OOM recovery, zombie cleanup
- dataset cache, immutable snapshot, checkpoint push, GPU Direct Storage readiness plan
- RBAC, approval workflow, audit log, central log stream, FinOps usage/billing/budget/cost estimate
- Slack/Teams/Email/webhook alert, profiler wrapper, Prometheus text export
- Java 21 virtual thread 기반 gRPC server mode, Redis lock, PostgreSQL/Redis readiness check
- `system safety` 기반 물리적 손상 예방, 한도 초과 차단, heartbeat stale 감지, 사고 기록

### 설치와 빌드

요구 사항:

- Java 21 이상
- Gradle wrapper 포함
- 기본 DB: SQLite, `data/gpu-mgr.db`
- 선택 도구: `nvidia-smi`, `amd-smi`, `rocm-smi`, `xpu-smi`, `docker`, `kubectl`, `mlflow`, `bentoml`, `ssh`

빌드:

```powershell
.\gradlew.bat test gpumDistZip
java --enable-native-access=ALL-UNNAMED -jar build\libs\gpu-mgr.jar --version
```

배포 산출물:

```text
build/libs/gpu-mgr.jar
build/gpum-dist/
build/distributions/gpum-dist.zip
```

### 운영 흐름

1. 노드를 스캔합니다.
2. 안전 정책을 설정하고 사전 점검을 실행합니다.
3. RBAC 역할과 quota를 설정합니다.
4. GPU allocation을 dry-run으로 확인한 뒤 생성합니다.
5. allocation 환경 변수를 AI runtime에 연결합니다.
6. batch job, interactive session, Kubernetes manifest, runtime worker 중 하나로 실행합니다.
7. telemetry, alert, profiler, logs, reports로 상태와 비용을 추적합니다.
8. 작업 종료 후 allocation을 release하고 expired lease를 reap합니다.
9. DB backup과 audit trace로 운영 기록을 보존합니다.

### 안전 모델

`gpum`은 물리적 손상과 운영 사고를 줄이기 위해 다음 가드를 둡니다.

- hardware write는 기본 차단
- GPU power limit은 장치 min/max와 정책 max를 확인
- hard reset은 CLI에서 의도적으로 차단
- linked GPU reset은 `--allow-linked-reset` 없이는 차단
- ECC 변경은 `--allow-reboot-required` 없이는 차단
- active allocation이 있는 GPU 변경은 기본 차단
- safety policy가 `maxGpusPerRequest`, `maxLeaseHours`, `maxJobShmGb` 같은 한도를 강제
- thermal critical, power saturation, stale heartbeat, expired active lease를 `system safety check`에서 탐지
- 위험 작업에는 RBAC approval과 audit log를 남김

## English Guide

### Core Capabilities

- Multi-vendor GPU inventory for NVIDIA, AMD, and Intel devices
- Lease-based allocation lifecycle: request, extend, move, release, reap
- MIG/partition operations, topology-aware placement, best-fit/worst-fit placement, backfill
- CPU/RAM/PID quota planning, RDMA policy planning, accelerator registry
- Batch and interactive execution plans for Docker, Apptainer, Singularity, Jupyter, VS Code tunnel, and SSH
- Runtime workers, watchdogs, OOM recovery, container reconcile, migration planning, zombie cleanup
- Dataset cache, immutable snapshot metadata, checkpoint movement, GPU Direct Storage readiness
- RBAC, approval workflow, audit trail, centralized logs, FinOps reports and estimates
- Alerts, profiler wrappers, Prometheus export
- Java 21 virtual-thread gRPC server with allocation, release, submit, heartbeat, telemetry, health, and lock endpoints
- Safety policy and preflight checks to prevent physical damage, quota overrun, stale nodes, and unsafe execution

### Install and Build

Requirements:

- Java 21 or newer
- Included Gradle wrapper
- Default SQLite DB at `data/gpu-mgr.db`
- Optional vendor and platform tools: `nvidia-smi`, `amd-smi`, `rocm-smi`, `xpu-smi`, `docker`, `kubectl`, `mlflow`, `bentoml`, `ssh`

Build:

```powershell
.\gradlew.bat test gpumDistZip
java --enable-native-access=ALL-UNNAMED -jar build\libs\gpu-mgr.jar --version
```

### Operating Flow

1. Scan nodes and persist inventory.
2. Configure safety limits and run a safety preflight.
3. Grant RBAC roles and define quotas.
4. Dry-run an allocation, then create the lease.
5. Render allocation-scoped AI environment variables.
6. Execute through batch jobs, sessions, Kubernetes manifests, or runtime workers.
7. Observe with telemetry, alerts, profiler plans, logs, and cost reports.
8. Release allocations and reap expired leases.
9. Preserve evidence through audit traces and database backups.

### Safety Model

`gpum` is designed as a guarded operations tool. Hardware mutation and external execution are opt-in, policy-limited, and auditable. The `system safety` command family provides cluster guardrails for thermal thresholds, power limits, GPU request limits, lease duration, shared memory size, heartbeat staleness, and disk headroom.

## Command Reference and Examples

All examples use dummy values. Replace IDs, hostnames, images, paths, URLs, and timestamps with your environment values.

모든 예시는 임의 값입니다. ID, 호스트명, 이미지, 경로, URL, 시간 값은 실제 환경 값으로 바꿔 사용하세요.

### Global Options

```bash
gpum --help
gpum --version
gpum --db data/example.db node list
gpum --config gpum.example.yaml system config
gpum --command-timeout-sec 30 system health
```

### Node Commands

Node commands create the inventory foundation. Allocation, topology placement, safety checks, and reports all depend on this data.

노드 명령은 인벤토리의 시작점입니다. allocation, topology placement, safety check, report가 이 데이터를 사용합니다.

```bash
gpum node scan --force --discovery-depth 2
gpum node scan --ip 10.10.0.11 --ssh-user gpuadmin --force
gpum node scan --all --discovery-depth 1
gpum node list --sort gpu
gpum node info gpu-node-a
gpum node top --metric util
gpum node drain gpu-node-a --graceful --timeout 300 --reason "kernel patch" --evict
gpum node undrain gpu-node-a
gpum node maintenance gpu-node-a --on --reason "thermal inspection"
gpum node maintenance gpu-node-a --off
gpum node label gpu-node-a --set zone=nvlink,team=vision
gpum node label gpu-node-a --show
gpum node label gpu-node-a --remove zone,team
gpum node remote add --ip 10.10.0.12 --ssh-user gpuadmin --alias rack-a-02
gpum node remote list
gpum node remote remove --ip 10.10.0.12
```

### GPU Commands

GPU commands inspect devices and preview guarded hardware changes. Apply paths require explicit approval and hardware-write enablement.

GPU 명령은 장치를 확인하고 안전 가드가 적용된 하드웨어 변경을 미리 봅니다. 실제 적용은 명시적 승인과 hardware-write 활성화가 필요합니다.

```bash
gpum gpu list
gpum gpu list --available --min-vram 80000 --capability mig --pci-gen 5
gpum gpu stats
gpum gpu stats --json
gpum gpu stats --export csv
gpum gpu stats --export influxdb
gpum gpu health --check-ecc --thermal-test --memory-test --report
gpum gpu health --score --quarantine-threshold 40
gpum gpu topology --visualize

# Dry-run hardware changes.
gpum gpu set --id gpu-node-a:0 --power-limit 300
gpum gpu set --id gpu-node-a:0 --ecc on --allow-reboot-required
gpum gpu set --id gpu-node-a:0 --compute-mode exclusive_process
gpum gpu reset --id gpu-node-a:0 --soft --drain-first

# Real hardware mutation requires role, approval, --apply, and env opt-in.
GPUM_ENABLE_HARDWARE_WRITE=1 gpum gpu set --id gpu-node-a:0 --power-limit 300 --apply --approval-id appr-example
GPUM_ENABLE_HARDWARE_WRITE=1 gpum gpu reset --id gpu-node-a:0 --soft --drain-first --apply --approval-id appr-example
gpum gpu set --id gpu-node-b:0 --power-limit 280 --via-agent --ssh-user gpuadmin
```

### Allocation Commands

Allocation creates the lease that downstream job, integration, runtime, quota, and cost commands reference.

Allocation은 job, integration, runtime, quota, cost 명령이 이어서 사용하는 lease입니다.

```bash
gpum alloc estimate --model llama --params-b 13 --precision fp16 --context 8192 --batch 2
gpum alloc request --gpus 1 --vram 60000 --hours 4 --label-selector role=trainer --dry-run
gpum alloc request --gpus 2 --model H100 --vram 80000 --tenant research --priority 8 --affinity packed
gpum alloc request --gpus 4 --exclusive --preemptible --label-selector zone=nvlink
gpum alloc list
gpum alloc list --mine --status active
gpum alloc list --tenant research --node gpu-node-a
gpum alloc info --id alloc-example
gpum alloc extend --id alloc-example --hours 2 --reason "experiment still running"
gpum alloc move --id alloc-example --to-node gpu-node-b
gpum alloc release --id alloc-example
gpum alloc release --id alloc-example --kill-process
gpum alloc release --id alloc-example --force --kill-process
gpum alloc reap
```

### Compute Commands

Compute commands add CPU/RAM/PID, RDMA, and accelerator policy around a GPU allocation.

Compute 명령은 GPU allocation 주변에 CPU/RAM/PID, RDMA, accelerator 정책을 붙입니다.

```bash
gpum compute quota --allocation-id alloc-example --cpu-cores 8 --memory-mb 65536 --pids 512
gpum compute quota --allocation-id alloc-example --cpu-cores 8 --memory-mb 65536 --pid 12345 --execute
gpum compute rdma --name ib-train --node gpu-node-a --device ib0 --bandwidth-mbit 100000 --priority 1
gpum compute rdma --name ib-train --node gpu-node-a --device ib0 --bandwidth-mbit 100000 --priority 1 --execute
gpum compute accelerator register --name edge-npu-a --kind npu --driver vendor-npu --endpoint gpu-node-a:/dev/npu0 --label team=vision
gpum compute accelerator list
gpum compute model-quota --name team-a-h100 --tenant team-a --gpu-model H100 --max-gpus 16
```

### Scheduling Commands

Scheduling commands decide where and when work should run when resources are contested.

Scheduling 명령은 자원이 부족하거나 경합될 때 어떤 작업을 어디에 언제 실행할지 결정합니다.

```bash
gpum schedule queue create --name research --tenant research --weight 10 --max-gpus 32 --preemptible
gpum schedule queue list
gpum schedule reserve create --name nightly-ddp --queue research --start 2030-01-01T00:00:00Z --end 2030-01-01T04:00:00Z --gpus 8 --nodes 2 --project llm
gpum schedule reserve list
gpum schedule reserve cancel --id reservation-example
gpum schedule fair-share --owner alice --window-hours 168
gpum schedule gang --name ddp-8node --nodes 8 --gpus-per-node 8 --label-selector zone=nvlink --reserve
gpum schedule preempt --name urgent-train --victim-allocation-id alloc-low --incoming alloc-high --suspend-command "kill -STOP 12345" --resume-command "kill -CONT 12345"
gpum schedule preempt --name urgent-train --victim-allocation-id alloc-low --incoming alloc-high --execute
gpum schedule place --gpus 4 --vram 80000 --model H100 --strategy best-fit
gpum schedule place --gpus 4 --strategy worst-fit
gpum schedule place --gpus 4 --strategy topology
gpum schedule backfill --queue research --max-minutes 45 --max-gpus 2
```

### Data Commands

Data commands prepare datasets and checkpoints so GPU jobs do not stall on I/O.

Data 명령은 GPU job이 I/O 병목으로 멈추지 않도록 dataset과 checkpoint 경로를 준비합니다.

```bash
gpum data cache --name imagenet-cache --source s3://example-bucket/imagenet --target D:\gpum-cache\imagenet
gpum data cache --name local-cache --source \\nfs\datasets\imagenet --target D:\gpum-cache\imagenet --execute
gpum data snapshot --name imagenet-v1 --source s3://example-bucket/imagenet --version v1 --mount D:\snapshots\imagenet-v1
gpum data checkpoint --name run42-ckpt --source D:\runs\run42\checkpoints --dest s3://example-bucket/checkpoints/run42
gpum data checkpoint --name run42-ckpt --source D:\runs\run42\checkpoints --dest D:\backup\run42 --execute
gpum data gds --name gds-read --mount D:\datasets --mode read
```

### Job Commands

Job commands turn an allocation into an execution plan or runtime worker.

Job 명령은 allocation을 실제 실행 계획이나 runtime worker로 연결합니다.

```bash
gpum job batch --name train-llm --allocation-id alloc-example --command "python train.py"
gpum job batch --name train-docker --allocation-id alloc-example --image nvcr.io/nvidia/pytorch:24.12-py3 --engine docker --gpus all --shm-size 128g --command "python train.py"
gpum job batch --name train-apptainer --allocation-id alloc-example --image train.sif --engine apptainer --command "python train.py"
gpum job batch --name train-singularity --allocation-id alloc-example --image train.sif --engine singularity --command "python train.py"
gpum job batch --name train-exec --allocation-id alloc-example --command "python train.py" --execute
gpum job session --name jupyter-a --allocation-id alloc-example --kind jupyter --port 8899
gpum job session --name vscode-a --allocation-id alloc-example --kind vscode --port 8898
gpum job session --name ssh-a --allocation-id alloc-example --kind ssh --port 2222
gpum job list
```

### Partition Commands

Partition commands manage MIG-like partition metadata and guarded create/destroy paths.

Partition 명령은 MIG 계열 파티션 메타데이터와 안전 가드가 적용된 create/destroy 경로를 관리합니다.

```bash
gpum part create --gpu gpu-node-a:0 --profile 1g.10gb --count 2
gpum part create --gpu gpu-node-a:0 --profile 2g.20gb --count 1 --apply --approval-id appr-example
gpum part list
gpum part destroy --id part-example
gpum part destroy --id part-example --apply --approval-id appr-example
gpum part auto-optimize
```

### Queue Commands

Queue commands handle allocation requests that could not be placed immediately.

Queue 명령은 즉시 배치되지 못한 allocation 요청을 운영자가 조정할 때 사용합니다.

```bash
gpum queue list
gpum queue list --full --position my --estimate
gpum queue promote --id queue-example --val 5
gpum queue demote --id queue-example --val 2
```

### Quota Commands

Quota commands enforce user or tenant limits before allocation.

Quota 명령은 allocation 전에 사용자 또는 tenant 한도를 강제합니다.

```bash
gpum quota set --name alice --max-gpus 4 --max-vram 320000 --max-lease-hours 72
gpum quota alert --name alice --threshold 80,90
gpum quota status --user alice --remaining
```

### Audit and Log Commands

Audit is for lifecycle evidence. Logs are for operator-readable events.

Audit은 수명주기 증적이고, Log는 운영자가 읽는 이벤트 기록입니다.

```bash
gpum audit list --tail 50
gpum audit list --actor alice --action ALLOC_CREATE
gpum audit trace alloc-example

gpum log write --level info --component scheduler --category placement --message "placed job" --context alloc-example
gpum log list --component scheduler --contains placed --sort desc --limit 20
gpum log tail --lines 20
```

### Observability Commands

Observability commands connect jobs to alerts, profiler plans, metrics, and log streams.

Observability 명령은 job을 alert, profiler, metrics, log stream에 연결합니다.

```bash
gpum observe alert create --name slack-done --channel slack --target https://hooks.example/services/T000/B000/XXX --event job.done
gpum observe alert create --name email-error --channel email --target mlops@example.com --event job.error --template "gpum {{event}} {{resource}}"
gpum observe alert list
gpum observe profile --name profile-train --allocation-id alloc-example --tool nsys --command "python train.py"
gpum observe profile --name profile-kernel --allocation-id alloc-example --tool ncu --command "python train.py" --execute
gpum observe telemetry --name fast-gpu --interval-sec 5 --retention-hours 24
gpum observe telemetry --name snapshot --interval-sec 5 --retention-hours 24 --path D:\metrics\gpum.prom --execute
gpum observe log-stream --lines 50
gpum observe log-stream --component job --lines 100 --follow
```

### Integration Commands

Integration commands bridge allocations into Kubernetes, MLflow, BentoML, shell tools, and AI launchers.

Integration 명령은 allocation을 Kubernetes, MLflow, BentoML, 외부 도구, AI launcher로 연결합니다.

```bash
gpum integration k8s contexts
gpum integration k8s pods --namespace research
gpum integration k8s submit --name trainer --image repo/train:latest --gpus 2 --namespace research --kind Job --allocation-id alloc-example
gpum integration k8s submit --name nightly --image repo/train:latest --kind CronJob --schedule "0 2 * * *" --env RUN_ID=example --secret-env WANDB_API_KEY=wandb --dataset-pvc imagenet-pvc --dataset-mount /datasets
gpum integration k8s logs trainer-pod-example --namespace research

gpum integration mlflow status
gpum integration mlflow runs --experiment llm --limit 20
gpum integration mlflow models --limit 20

gpum integration bentoml list
gpum integration bentoml models
gpum integration bentoml serve --bento fraud_detector:latest --port 3000

gpum integration ai env --allocation-id alloc-example --format shell
gpum integration ai env --allocation-id alloc-example --format json
gpum integration ai launch --allocation-id alloc-example --tool python --arg train.py --arg --epochs --arg 3
gpum integration ai launch --allocation-id alloc-example --tool python --from-file args.txt --via-ssh --ssh-user gpuadmin
gpum integration ai preset list
gpum integration ai preset render --allocation-id alloc-example --name torchrun-ddp --entrypoint train.py --arg --epochs --arg 3
gpum integration ai preset launch --allocation-id alloc-example --name accelerate --entrypoint train.py --execute

gpum integration tool --name ray --arg status
```

### Report Commands

Reports convert allocation and usage records into operational and FinOps views.

Report 명령은 allocation과 usage 기록을 운영 및 FinOps 관점으로 변환합니다.

```bash
gpum report usage --format json --by user
gpum report usage --format csv --by tenant
gpum report usage --format pdf --by model
gpum report billing --rate-card rate-card.yaml
gpum report prometheus
gpum report prometheus --path D:\metrics\gpum.prom
gpum report budget --name monthly-alice --owner alice --budget 1000 --rate-per-gpu-hour 3.5 --window-hours 720
gpum report cost-estimate --owner alice --gpu-model H100 --gpus 4 --hours 8 --rate-per-gpu-hour 3.5
```

### RBAC Commands

RBAC commands define who can apply risky actions. Approval records are required for high-risk hardware paths.

RBAC 명령은 위험 작업을 누가 실행할 수 있는지 정의합니다. 고위험 하드웨어 작업은 approval 기록이 필요합니다.

```bash
gpum rbac whoami
gpum rbac role grant --actor alice --role admin
gpum rbac role grant --actor bob --role operator --tenant research
gpum rbac role list
gpum rbac role list --actor bob
gpum rbac role revoke --actor bob --role operator --tenant research
gpum rbac approval list
gpum rbac approval list --status pending --mine
gpum rbac approval approve --id appr-example --reason "maintenance approved"
gpum rbac approval deny --id appr-example --reason "outside maintenance window"
```

### Runtime Commands

Runtime commands manage workers, watchdogs, OOM recovery, migration, reconcile, and stale GPU processes.

Runtime 명령은 worker, watchdog, OOM recovery, migration, reconcile, stale GPU process를 관리합니다.

```bash
gpum runtime native metrics
gpum runtime worker register --id worker-a --allocation-id alloc-example --tenant research --owner alice --command "python train.py" --env WANDB_MODE=offline --checkpoint-command "python save.py" --restore-command "python restore.py" --max-restarts 3 --max-lifetime-min 1440 --memory-restart-mb 240000
gpum runtime worker list
gpum runtime worker start --id worker-a
gpum runtime worker stop --id worker-a
gpum runtime worker stop --id worker-a --force
gpum runtime worker restart --id worker-a --reason "manual retry"
gpum runtime worker restart --id worker-a --force --reason "hung dataloader"
gpum runtime worker recycle
gpum runtime worker recycle --execute
gpum runtime worker events --id worker-a --limit 50
gpum runtime daemon run --once
gpum runtime daemon run --interval-sec 30 --execute
gpum runtime oom handle --allocation-id alloc-example --strategy restart
gpum runtime oom handle --allocation-id alloc-example --strategy defrag --execute
gpum runtime oom handle --allocation-id alloc-example --strategy stop --execute
gpum runtime oom handle --allocation-id alloc-example --strategy release --execute
gpum runtime reconcile docker
gpum runtime reconcile k8s
gpum runtime migrate plan --worker-id worker-a --to-node gpu-node-b
gpum runtime migrate plan --worker-id worker-a --to-node gpu-node-b --execute
gpum runtime zombie list --gpu-id gpu-node-a:0
gpum runtime zombie clean --gpu-id gpu-node-a:0
gpum runtime zombie clean --gpu-id gpu-node-a:0 --force --execute
```

### Secret Commands

Secret commands store references, not raw secret values by default.

Secret 명령은 기본적으로 실제 secret 값이 아니라 참조만 저장합니다.

```bash
gpum secret put --name wandb --provider env --ref WANDB_API_KEY --env WANDB_API_KEY
gpum secret put --name db-pass --provider vault --ref secret/mlops/db/password --env DB_PASSWORD
gpum secret list
gpum secret render --id ref-example --format shell
gpum secret render --id ref-example --format cmd
gpum secret render --id ref-example --format json
```

### Developer Commands

Developer commands make the CLI easier to install and integrate.

Developer 명령은 CLI 설치와 연동을 쉽게 합니다.

```bash
gpum dev completion --shell bash
gpum dev completion --shell zsh
gpum dev completion --shell powershell
gpum dev native
gpum dev terminal
gpum dev python-sdk --output generated/gpum_client.py
```

### Server Commands

Server commands expose centralized allocation and execution through gRPC.

Server 명령은 gRPC를 통해 중앙 allocation과 실행 제어를 제공합니다.

```bash
gpum server run --port 7070
gpum server run --port 0 --once
gpum server health --host 127.0.0.1 --port 7070
gpum server resources --host 127.0.0.1 --port 7070
gpum server allocate --host 127.0.0.1 --port 7070 --tenant research --owner alice --gpus 2 --model H100 --vram 80000 --hours 4 --affinity packed --dry-run
gpum server allocate --host 127.0.0.1 --port 7070 --tenant research --owner alice --gpus 2 --label-selector zone=nvlink --exclusive-node
gpum server submit --host 127.0.0.1 --port 7070 --allocation-id alloc-example --name train-remote --image nvcr.io/nvidia/pytorch:24.12-py3 --engine docker --gpus all --shm-size 128g --command "python train.py"
gpum server release --host 127.0.0.1 --port 7070 --id alloc-example
gpum server heartbeat --host 127.0.0.1 --port 7070 --node gpu-node-a --status ALIVE --labels zone=nvlink,team=research --allocatable-gpus 8
gpum server telemetry --host 127.0.0.1 --port 7070 --filter gpu
gpum server storage
gpum server lock --key alloc:H100:0 --owner scheduler --ttl-ms 30000
gpum server lock --key alloc:H100:0 --owner scheduler --release
```

Optional server backend environment:

```bash
export GPUM_POSTGRES_URL=jdbc:postgresql://localhost:5432/gpum
export GPUM_POSTGRES_USER=gpum
export GPUM_POSTGRES_PASSWORD=example-password
export GPUM_REDIS_URL=redis://localhost:6379
gpum server storage
```

gRPC protocol document:

```text
src/main/proto/gpum.proto
```

### System and Safety Commands

System commands maintain configuration, database health, backups, and safety guardrails.

System 명령은 설정, DB 상태, backup, 안전 한도를 관리합니다.

```bash
gpum system config
gpum system config --show-defaults
gpum system config --reload
gpum system config --edit
gpum system db-check
gpum system db-check --repair --vacuum --orphan-clean
gpum system health

gpum system safety limits
gpum system safety policy --max-gpus-per-request 16 --max-lease-hours 168 --thermal-warn-c 75 --thermal-critical-c 85 --min-free-vram-ratio 0.05 --max-power-limit-w 900 --min-disk-free-gb 20 --heartbeat-stale-sec 120 --max-job-shm-gb 512
gpum system safety check
gpum system safety check --quarantine
gpum system safety check --fail-on-warn
gpum system safety incident --node gpu-node-a --gpu-id 0 --severity warning --action quarantine --message "thermal anomaly"
gpum system safety incident --node gpu-node-a --severity critical --action drain --message "fan failure"

gpum system backup --path backups/gpum-backup.db
gpum system restore --path backups/gpum-backup.db
gpum system update
```

## End-to-End Recipes

### Local Training Flow

```bash
gpum node scan --force --discovery-depth 2
gpum system safety policy --max-gpus-per-request 8 --max-lease-hours 72 --thermal-warn-c 75 --thermal-critical-c 85 --min-free-vram-ratio 0.05 --max-power-limit-w 900 --min-disk-free-gb 20 --heartbeat-stale-sec 120 --max-job-shm-gb 256
gpum system safety check
gpum rbac role grant --actor alice --role operator --tenant research
gpum quota set --name alice --max-gpus 4 --max-vram 320000 --max-lease-hours 72
gpum alloc request --gpus 2 --vram 80000 --hours 8 --tenant research --label-selector role=trainer --dry-run
gpum alloc request --gpus 2 --vram 80000 --hours 8 --tenant research --label-selector role=trainer
gpum integration ai env --allocation-id alloc-example --format shell
gpum job batch --name train --allocation-id alloc-example --image nvcr.io/nvidia/pytorch:24.12-py3 --engine docker --gpus all --shm-size 128g --command "python train.py"
gpum observe telemetry --name train-telemetry --interval-sec 5 --retention-hours 24
gpum report cost-estimate --owner alice --gpu-model H100 --gpus 2 --hours 8 --rate-per-gpu-hour 3.5
gpum alloc release --id alloc-example --kill-process
gpum audit trace alloc-example
```

### Central Server Flow

```bash
gpum server run --port 7070
gpum server heartbeat --host 127.0.0.1 --port 7070 --node gpu-node-a --status ALIVE --labels zone=nvlink --allocatable-gpus 8
gpum server allocate --host 127.0.0.1 --port 7070 --tenant research --owner alice --gpus 2 --vram 80000 --hours 8 --dry-run
gpum server allocate --host 127.0.0.1 --port 7070 --tenant research --owner alice --gpus 2 --vram 80000 --hours 8
gpum server submit --host 127.0.0.1 --port 7070 --allocation-id alloc-example --name train --image nvcr.io/nvidia/pytorch:24.12-py3 --command "python train.py"
gpum server telemetry --host 127.0.0.1 --port 7070 --filter gpu
gpum server release --host 127.0.0.1 --port 7070 --id alloc-example
```

### Incident Response Flow

```bash
gpum system safety check --quarantine
gpum system safety incident --node gpu-node-a --gpu-id 0 --severity critical --action quarantine --message "thermal critical"
gpum node drain gpu-node-a --graceful --timeout 300 --reason "thermal critical" --evict
gpum runtime zombie list --gpu-id gpu-node-a:0
gpum runtime zombie clean --gpu-id gpu-node-a:0 --force --execute
gpum audit list --tail 100
gpum system backup --path backups/post-incident.db
```

## Operational Notes

- `--execute` runs external commands. Without it, most integration and orchestration commands only print a plan.
- `--apply` is for guarded hardware mutation and still requires role, approval, and `GPUM_ENABLE_HARDWARE_WRITE=1`.
- `system safety policy` stores limits in `ops_records`; allocation and server allocation read these limits.
- `job batch` validates execution engine and shared-memory size against safety policy.
- `job batch`, `job session`, `observe profile`, `compute quota`, and `schedule preempt` validate referenced allocation IDs before recording or executing dependent work.
- CLI parse and execution failures are normalized into `ERROR` plus `HINT` messages.
- Compute policy inputs have upper bounds for CPU cores, memory, PIDs, GPU model quota, and RDMA bandwidth to prevent accidental runaway values.
- `system safety check --fail-on-warn` is suitable for CI or maintenance gates.
- SQLite is the default local persistence path. PostgreSQL and Redis are optional readiness/integration paths for server deployments.
- Every registered command supports `-h` and `--help`.
