# gpum

`gpum`은 AI 학습과 추론 서버를 위한 GPU 운영 CLI입니다. NVIDIA, AMD, Intel GPU 인벤토리 수집, 할당, 감사 로그, 쿼터, 승인 워크플로, AI 런타임 실행 보조, Kubernetes 연동을 하나의 명령 표면으로 묶습니다.

> [!IMPORTANT]
> 현재 릴리즈 기준 문서는 `v1.0.1`입니다. 하드웨어를 실제로 변경하는 명령은 기본적으로 dry-run이며, 명시적인 환경 변수와 RBAC 승인 절차를 통과해야 실행됩니다.

> [!NOTE]
> 실제 GPU가 없어도 대부분의 흐름을 테스트할 수 있도록 detector fixture, 혼합 벤더 테스트, SQLite 기반 서비스 테스트가 포함되어 있습니다.

## 주요 기능

- NVIDIA / AMD / Intel GPU 탐지
- Windows Intel Arc / Arc Pro / Flex / Max PowerShell fallback 탐지
- 로컬 및 SSH 기반 원격 노드 스캔
- SQLite 기반 인벤토리, 할당, 감사, 로그, 런타임 상태 저장
- GPU 할당 요청, dry-run 배치, 연장, 이동, 반납, 만료 정리
- 큐, 쿼터, 파티션 레코드, 사용량 및 과금 리포트
- GPU 상태 점수, quarantine 플래그, Prometheus export
- RBAC role binding 및 approval workflow
- 안전장치가 있는 GPU power/ECC/compute-mode/reset apply 경로
- NVIDIA MIG 파티션 create/destroy apply 경로
- allocation 기반 AI launcher 환경 생성 및 실행 보조
- Kubernetes Job/CronJob/Deployment manifest render/apply
- runtime worker 등록, start/stop/restart, OOM 처리, checkpoint migration plan

## 요구 사항

- Java 21 이상
- 빌드: Gradle wrapper 포함
- 데이터 저장소: SQLite, 기본 경로 `data/gpu-mgr.db`
- 전체 하드웨어 메트릭을 보려면 대상 서버에 벤더 CLI 설치 권장:
  - NVIDIA: `nvidia-smi`
  - AMD: `amd-smi` 또는 `rocm-smi`
  - Intel: `xpu-smi`

외부 연동을 사용할 경우 `kubectl`, `mlflow`, `bentoml`, `docker`, `ssh`가 필요합니다.

## 설치

Windows:

```bat
install.cmd
```

기본 설치 위치:

```text
%LocalAppData%\gpum
```

Linux:

```sh
curl -fsSL https://raw.githubusercontent.com/drewdrew0414/AIGPUManager/main/install-gpum.sh | sh
```

기본 설치 위치:

```text
$HOME/.local/bin
```

Portable 실행도 가능합니다. `gpu-mgr.jar`와 launcher를 같은 디렉터리에 두면 됩니다.

- Windows CMD: `gpum.cmd`
- PowerShell: `gpum.ps1`
- Linux/macOS shell: `gpum`

## 빌드와 테스트

```bash
./gradlew test
./gradlew gpumDistZip
```

생성물:

- `build/libs/gpu-mgr.jar`
- `build/gpum-dist`
- `build/distributions/gpum-dist.zip`

Windows PowerShell에서는 다음처럼 실행할 수 있습니다.

```powershell
.\gradlew.bat test
.\gradlew.bat gpumDistZip
```

## 빠른 시작

로컬 노드 스캔:

```bash
gpum node scan
gpum node list
gpum node info
gpum gpu list
```

GPU 상태 확인:

```bash
gpum gpu stats --json
gpum gpu health --score
gpum gpu topology --visualize
```

할당 dry-run 및 요청:

```bash
gpum alloc request --gpus 1 --vram 60000 --hours 4 --dry-run
gpum alloc request --gpus 1 --vram 60000 --hours 4
gpum alloc list
gpum alloc info --id <allocation-id>
```

원격 노드 등록 및 스캔:

```bash
gpum node remote add --ip <remote-ip> --ssh-user <ssh-user> --alias trainer-a
gpum node remote list
gpum node scan --ip <remote-ip> --ssh-user <ssh-user>
```

## 명령 그룹

### `node`

노드 인벤토리와 운영 상태를 관리합니다.

```bash
gpum node scan
gpum node scan --all
gpum node scan --ip <remote-ip> --ssh-user <ssh-user>
gpum node list
gpum node info
gpum node info <host>
gpum node top --metric util
gpum node drain
gpum node undrain
gpum node maintenance --on --reason patching
gpum node label --show
gpum node label --set role=trainer,zone=lab
gpum node remote add --ip <remote-ip> --ssh-user <ssh-user>
gpum node remote list
gpum node remote remove --ip <remote-ip>
```

`node drain`, `node undrain`, `node maintenance`, `node label`은 `HOST`를 생략하면 로컬 호스트를 대상으로 합니다.

### `gpu`

GPU 조회, 통계, 상태 점수, topology, 하드웨어 제어 요청을 다룹니다.

```bash
gpum gpu list
gpum gpu list --available --min-vram 80000
gpum gpu list --capability mig --pci-gen 5
gpum gpu stats
gpum gpu stats --json
gpum gpu stats --export csv
gpum gpu stats --export influxdb
gpum gpu health --check-ecc --thermal-test --memory-test --report
gpum gpu health --score --quarantine-threshold 40
gpum gpu topology --visualize
```

하드웨어 변경은 기본적으로 preview입니다.

```bash
gpum gpu set --id <node>:<gpu-id> --power-limit 300
gpum gpu reset --id <node>:<gpu-id> --soft
```

실제 적용은 별도 안전장치를 통과해야 합니다.

```bash
export GPUM_ENABLE_HARDWARE_WRITE=1
gpum gpu set --id <node>:<gpu-id> --power-limit 300 --apply --approval-id <approval-id>
gpum gpu reset --id <node>:<gpu-id> --soft --apply --approval-id <approval-id>
```

Windows CMD:

```bat
set GPUM_ENABLE_HARDWARE_WRITE=1
gpum gpu reset --id <node>:<gpu-id> --soft --apply --approval-id <approval-id>
```

원격 agent 적용:

```bash
gpum gpu set --id <remote-node>:<gpu-id> --power-limit 320 --via-agent --apply --approval-id <approval-id>
```

### `alloc`

GPU 할당 생명주기를 관리합니다.

```bash
gpum alloc request --gpus 1 --vram 60000 --hours 4
gpum alloc request --gpus 2 --affinity packed --dry-run
gpum alloc list
gpum alloc info --id <allocation-id>
gpum alloc extend --id <allocation-id> --hours 2
gpum alloc release --id <allocation-id>
gpum alloc release --id <allocation-id> --kill-process
gpum alloc move --id <allocation-id> --to-node <node>
gpum alloc reap
gpum alloc estimate --model llama --params-b 70 --precision fp16 --context 8192 --batch 1
```

할당기는 VRAM, health quarantine, node 상태, topology hint를 함께 고려합니다.

### `rbac`

역할과 승인 요청을 관리합니다.

```bash
gpum rbac whoami
gpum rbac role grant --actor alice --role operator
gpum rbac role grant --actor bob --role approver --tenant research
gpum rbac role list
gpum rbac role revoke --actor alice --role operator
gpum rbac approval list --status pending
gpum rbac approval approve --id <approval-id> --reason "approved window"
gpum rbac approval deny --id <approval-id> --reason "not approved"
```

`gpu set --apply`, `gpu reset --apply`, `part create --apply`, `part destroy --apply` 같은 고위험 작업은 승인 요청을 만들 수 있습니다.

### `part`

논리 파티션과 NVIDIA MIG 적용 경로를 다룹니다.

```bash
gpum part create --gpu <node>:<gpu-id> --profile <profile> --count 1
gpum part create --gpu <node>:<gpu-id> --profile <profile> --count 1 --apply --approval-id <approval-id>
gpum part list
gpum part destroy --id <partition-id>
gpum part destroy --id <partition-id> --apply --approval-id <approval-id>
gpum part auto-optimize
```

MIG mode enable/disable은 자동화하지 않습니다. AMD와 Intel 파티션은 현재 논리 레코드 중심입니다.

### `runtime`

AI worker와 운영 런타임 상태를 관리합니다.

```bash
gpum runtime native metrics
gpum runtime worker register --id worker-1 --allocation-id <allocation-id> --command "python train.py"
gpum runtime worker register --id worker-2 --command "python train.py" --checkpoint-command "python save.py" --restore-command "python restore.py"
gpum runtime worker list
gpum runtime worker start --id worker-1
gpum runtime worker stop --id worker-1
gpum runtime worker restart --id worker-1 --reason oom
gpum runtime worker recycle
gpum runtime worker recycle --execute
gpum runtime worker events --id worker-1
gpum runtime daemon run --once
gpum runtime daemon run --interval-sec 30 --execute
gpum runtime oom handle --allocation-id <allocation-id> --strategy restart
gpum runtime oom handle --allocation-id <allocation-id> --strategy defrag --execute
gpum runtime reconcile docker
gpum runtime reconcile k8s
gpum runtime migrate plan --worker-id worker-2 --to-node <node>
gpum runtime migrate plan --worker-id worker-2 --to-node <node> --execute
```

마이그레이션은 checkpoint/restore 명령 기반입니다. 투명한 GPU 메모리 hot migration은 범위 밖입니다.

### `integration`

Kubernetes, MLflow, BentoML, AI launcher, custom tool 연동을 제공합니다.

```bash
gpum integration k8s contexts
gpum integration k8s pods
gpum integration k8s logs <pod>
gpum integration k8s submit --name train --image pytorch/pytorch --gpus 1
gpum integration k8s submit --name train --image pytorch/pytorch --allocation-id <allocation-id>
gpum integration k8s submit --name train --template job.yaml --allocation-id <allocation-id>
gpum integration k8s submit --name train --image pytorch/pytorch --dataset-pvc datasets --secret-env API_KEY=my-secret:key
gpum integration k8s submit --name train --image pytorch/pytorch --allocation-id <allocation-id> --execute
```

AI 런처:

```bash
gpum integration ai env --allocation-id <allocation-id>
gpum integration ai env --allocation-id <allocation-id> --format json
gpum integration ai launch --allocation-id <allocation-id> --tool torchrun --arg train.py
gpum integration ai launch --allocation-id <allocation-id> --tool accelerate --arg launch --arg train.py --execute
gpum integration ai launch --allocation-id <allocation-id> --tool torchrun --from-file args.template
gpum integration ai preset list
gpum integration ai preset render --allocation-id <allocation-id> --name torchrun-ddp --entrypoint train.py
gpum integration ai preset render --allocation-id <allocation-id> --name slurm-sbatch --entrypoint train.py
gpum integration ai preset launch --allocation-id <allocation-id> --name accelerate --entrypoint train.py --execute
```

MLflow와 BentoML:

```bash
gpum integration mlflow status
gpum integration mlflow runs --limit 20
gpum integration mlflow models
gpum integration bentoml list
gpum integration bentoml models
gpum integration bentoml serve --bento <bento> --port 3000
```

Custom tool:

```bash
gpum integration tool --name ray --arg status
```

### `queue`, `quota`, `report`, `audit`, `log`, `system`

운영 보조 명령입니다.

```bash
gpum queue list --full --estimate
gpum queue promote --id <queue-id> --val 2
gpum queue demote --id <queue-id> --val 1

gpum quota set --name research --max-gpus 4 --max-vram 320000 --max-lease-hours 72
gpum quota status --user alice --remaining
gpum quota alert --name research --threshold 80,90

gpum report usage --format json --by model
gpum report billing --rate-card rate-card.json
gpum report prometheus
gpum report prometheus --path metrics.prom

gpum audit list --tail 20
gpum audit trace <resource-id>

gpum log write --level info --component system --category startup --message "boot ok"
gpum log list --sort desc --limit 20
gpum log tail --lines 20

gpum system config --show-defaults
gpum system config --edit
gpum system db-check --repair --vacuum --orphan-clean
gpum system health
gpum system backup --path backup.db
gpum system restore --path backup.db
gpum system update
```

## 안전장치

하드웨어 mutation은 opt-in입니다.

- `gpu set`과 `gpu reset`은 기본적으로 dry-run preview
- 실제 apply에는 `GPUM_ENABLE_HARDWARE_WRITE=1` 필요
- 고위험 apply는 approval role 또는 승인 ID 필요
- 로컬 apply는 대상 GPU가 로컬 노드에 있어야 함
- 원격 apply는 등록된 remote node와 `--via-agent` 필요
- allocation 충돌, linked-fabric reset, reboot-required ECC 변경을 검사
- vendor power limit은 apply 시점에 다시 검증

승인 예시:

```bash
gpum gpu reset --id <node>:<gpu-id> --soft --apply
gpum rbac approval list --status pending
gpum rbac approval approve --id <approval-id> --reason "maintenance approved"
gpum gpu reset --id <node>:<gpu-id> --soft --apply --approval-id <approval-id>
```

## 설정

예제 파일:

```text
gpum.example.yaml
```

사용:

```bash
gpum --config gpum.example.yaml system config
```

주요 설정 영역:

- `tools`: vendor CLI, `ssh`, `docker`, `kubectl`, `mlflow`, `bentoml`, shell, remote `gpum` agent command
- `kubernetes`: namespace, service account, GPU resource key
- `mlflow`: tracking URI, registry URI, experiment
- `bentoml`: endpoint, home, working directory
- `monitoring`: scan interval, quarantine threshold, thermal threshold
- `externalTools`: Ray, Slurm 등 사용자 정의 명령

## 저장 데이터

기본 DB:

```text
data/gpu-mgr.db
```

저장 대상:

- nodes, GPUs, node attributes, labels
- remote node registrations
- allocations and claims
- queue entries
- partition records
- quota policies
- role bindings and approval requests
- runtime workers and runtime events
- audit events and operational logs

## 지원 GPU 계열

테스트 fixture 기준 대표 커버리지:

- NVIDIA: `H100`, `H200`, `B200`, `A100`, `RTX 4090`, `RTX 6000 Ada`, `L40S`
- AMD: `MI210`, `MI250X`, `MI300X`, `Radeon PRO W7900`
- Intel: `Data Center GPU Max 1100`, `Max 1550`, `Arc Pro A60`, `Flex 170`

Windows에서는 `xpu-smi`가 없을 때 Intel display adapter 모델명 기반 fallback 탐지를 수행합니다.

## 현재 한계

- hard GPU reset은 차단되어 있습니다.
- unsafe clock mutation은 차단되어 있습니다.
- MIG mode enable/disable은 자동화하지 않습니다.
- AMD와 Intel 파티션은 현재 논리 레코드 중심입니다.
- process cleanup은 로컬 노드와 PID 단위입니다.
- Docker/Kubernetes reconcile은 read-only drift detection입니다.
- runtime migration은 checkpoint/restore 기반입니다.
- NVML/Level Zero native telemetry는 기본 probe 수준입니다.
- NUMA, NIC, RDMA, storage locality 기반 고급 스케줄링은 아직 제한적입니다.
- fair-share queue aging과 cluster-wide preemption은 구현 범위 밖입니다.

## 라이선스

이 프로젝트는 [LICENSE](LICENSE)를 따릅니다.

---

## English Summary

`gpum` is a GPU operations CLI for AI training and inference servers. It provides multi-vendor GPU discovery, SQLite-backed inventory and allocation, audit/logging, quota and queue primitives, guarded hardware control, RBAC approvals, Kubernetes and AI runtime integrations, Prometheus export, and a worker runtime layer.

Quick start:

```bash
gpum node scan
gpum gpu list
gpum alloc request --gpus 1 --vram 60000 --hours 4 --dry-run
gpum integration ai env --allocation-id <allocation-id>
```

Hardware writes are disabled by default. Use dry-runs first, set `GPUM_ENABLE_HARDWARE_WRITE=1` only in trusted maintenance shells, and use the approval workflow for high-risk operations.
