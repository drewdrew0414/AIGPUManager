# gpum v1.0.1

Second public release of `gpum`, a multi-vendor GPU inventory, allocation, governance, and runtime operations CLI for AI training and inference servers.

## Release Summary

`v1.0.1` moves `gpum` beyond inventory and allocation basics. This release adds guarded hardware control paths, RBAC approval workflow, AI runtime handoff, native telemetry probes, Prometheus export, Kubernetes manifest rendering/apply support, and a SQLite-backed worker runtime layer.

The core direction is unchanged: `gpum` should be useful on a normal development machine, but structured enough to operate real GPU servers when vendor tooling and permissions are available.

---

## Highlights

### 1. Guarded GPU Hardware Control

`gpu set` and `gpu reset` now support real apply paths behind explicit safety gates.

Implemented guarded paths:

- `gpum gpu set --id <node>:<gpu-id> --power-limit <watts> --apply`
- `gpum gpu set --id <node>:<gpu-id> --ecc on|off --apply`
- `gpum gpu set --id <node>:<gpu-id> --compute-mode default|exclusive_process --apply`
- `gpum gpu reset --id <node>:<gpu-id> --soft --apply`
- remote apply through a registered SSH target with `--via-agent`

Safety behavior:

- dry-run remains the default
- real writes require `GPUM_ENABLE_HARDWARE_WRITE=1`
- apply checks local or remote-agent routing
- active allocations block writes unless explicitly allowed where supported
- ECC changes require explicit reboot-required acknowledgement
- high-risk apply operations require RBAC approval unless the actor already has the required role
- hard resets and unsafe clock fixing remain blocked

### 2. RBAC and Approval Workflow

New command group:

- `gpum rbac whoami`
- `gpum rbac role grant`
- `gpum rbac role revoke`
- `gpum rbac role list`
- `gpum rbac approval list`
- `gpum rbac approval approve`
- `gpum rbac approval deny`

High-risk operations can now create approval requests instead of failing silently or executing immediately.

Typical flow:

```bash
gpum gpu reset --id <node>:<gpu-id> --soft --apply
gpum rbac approval list --status pending
gpum rbac approval approve --id <approval-id> --reason "maintenance window"
gpum gpu reset --id <node>:<gpu-id> --soft --apply --approval-id <approval-id>
```

### 3. Runtime Worker Layer

New command group:

- `gpum runtime native metrics`
- `gpum runtime worker register`
- `gpum runtime worker list`
- `gpum runtime worker start`
- `gpum runtime worker stop`
- `gpum runtime worker restart`
- `gpum runtime worker recycle`
- `gpum runtime worker events`
- `gpum runtime daemon run`
- `gpum runtime oom handle`
- `gpum runtime reconcile docker`
- `gpum runtime reconcile k8s`
- `gpum runtime migrate plan`

Included behavior:

- SQLite-backed runtime worker registry
- restart budget tracking
- max-lifetime recycle preview and execution
- worker event history
- OOM recovery strategy hooks: `restart`, `defrag`, `stop`, `release`
- read-only Docker and Kubernetes allocation drift checks
- checkpoint/restore migration planning for registered workers

Migration is intentionally checkpoint-based. `gpum` does not claim transparent live GPU memory migration.

### 4. AI Tooling Integration

`gpum integration ai` can now turn an allocation into environment variables or a wrapped launch command.

Supported launch targets:

- `python`
- `torchrun`
- `accelerate`
- `deepspeed`
- `vllm`

Preset renderers:

- `torchrun-ddp`
- `accelerate`
- `deepspeed`
- `vllm-serve`
- `slurm-sbatch`
- `ray-job`

New or expanded commands:

- `gpum integration ai env --allocation-id <id>`
- `gpum integration ai env --allocation-id <id> --format json`
- `gpum integration ai launch --allocation-id <id> --tool torchrun --arg train.py`
- `gpum integration ai launch --allocation-id <id> --tool torchrun --from-file <template>`
- `gpum integration ai launch --allocation-id <id> --tool torchrun --via-ssh`
- `gpum integration ai preset list`
- `gpum integration ai preset render`
- `gpum integration ai preset launch`

Injected values include:

- `GPUM_ALLOCATION_ID`
- `GPUM_PRIMARY_NODE`
- `GPUM_GPU_COUNT`
- `GPUM_GPU_MODELS`
- `GPUM_GPU_UUIDS`
- `GPUM_NODE_HOSTS`
- `GPUM_NODE_COUNT`
- `MASTER_ADDR`
- `GPUM_RDZV_ENDPOINT`
- vendor visibility variables such as `CUDA_VISIBLE_DEVICES`, `ROCR_VISIBLE_DEVICES`, and `ZE_AFFINITY_MASK`

### 5. Kubernetes Submit Improvements

`gpum integration k8s submit` now renders or applies patched manifests for allocation-scoped workloads.

Added capabilities:

- `Job`, `CronJob`, and `Deployment` rendering
- optional user template input
- allocation-aware GPU request and node pinning
- environment injection
- secret-backed environment variables
- dataset PVC and additional PVC mount helpers
- optional `kubectl apply`
- watch, retry, and rollback-on-fail controls

Default mode still prints the manifest. Real apply requires `--execute`.

### 6. Health Scoring, Quarantine, and Prometheus Export

New operational visibility:

- `gpum gpu health --score`
- `gpum gpu health --score --quarantine-threshold <score>`
- `gpum report prometheus`
- `gpum report prometheus --path <file>`

Health scoring uses latest inventory metrics and monitoring thresholds. Quarantine marks low-score GPUs as unschedulable in the inventory metadata so allocation logic can avoid them.

Prometheus text export covers inventory, allocation, and health data.

### 7. Native Telemetry Probes

`gpum runtime native metrics` adds optional direct telemetry probes:

- NVIDIA NVML through JNA when available
- Intel Level Zero loader discovery when available
- clear `unavailable` rows when native libraries are not installed

This is intentionally minimal telemetry, not a replacement for vendor CLIs.

### 8. Allocation and Scheduling Improvements

Allocation behavior gained:

- topology-aware packed/spread placement hints
- NVLink, XGMI, and Xe Link interconnect awareness
- health quarantine awareness
- heuristic VRAM estimation:

```bash
gpum alloc estimate --model <model> --params-b <n> --precision fp16 --context <tokens> --batch <n>
```

Release cleanup can now optionally handle local processes:

```bash
gpum alloc release --id <allocation-id> --kill-process
```

Process cleanup is local-node and PID-scoped, with safety checks.

### 9. Partition Apply Path

Partition records remain cross-vendor, but `v1.0.1` adds guarded NVIDIA MIG apply support where the device already advertises MIG capability.

Commands:

- `gpum part create --gpu <node>:<gpu-id> --profile <profile> --count <n>`
- `gpum part create --gpu <node>:<gpu-id> --profile <profile> --count <n> --apply --approval-id <id>`
- `gpum part destroy --id <partition-id>`
- `gpum part destroy --id <partition-id> --apply --approval-id <id>`
- `gpum part auto-optimize`

MIG mode enable/disable is still intentionally manual.

### 10. Configuration Expansion

`gpum.example.yaml` now includes:

- `tools.gpumAgentCommand`
- `tools.docker`
- `tools.kubectl`
- `tools.powershell`
- `tools.cmd`
- `tools.bash`
- Kubernetes submit defaults
- monitoring thresholds
- external tool examples for Ray and Slurm

---

## Existing v1.0.0 Features Retained

`v1.0.1` keeps the original public surface:

- NVIDIA / AMD / Intel inventory
- Windows Intel display-adapter fallback detection
- local and SSH node scan
- node list / info / top / drain / undrain / maintenance / label
- GPU list / stats / health / topology
- allocation request / dry-run / list / info / extend / release / move / reap
- queue, quota, partition records
- usage and billing reports
- audit trail and operational logs
- SQLite persistence
- Windows and Linux launchers
- GitHub Release oriented install scripts

---

## Install

Windows:

```bat
install.cmd
```

Linux:

```sh
curl -fsSL https://raw.githubusercontent.com/drewdrew0414/AIGPUManager/main/install-gpum.sh | sh
```

Portable files:

- `gpu-mgr.jar`
- `gpum.cmd`
- `gpum.ps1`
- `gpum`

Distribution bundle:

- `gpum-dist.zip`

---

## Upgrade Notes

- If you use hardware apply commands, set `GPUM_ENABLE_HARDWARE_WRITE=1` only for trusted maintenance shells.
- Existing SQLite data remains the expected persistence path: `data/gpu-mgr.db`.
- RBAC approval tables and runtime worker tables are created by the SQLite repositories as needed.
- Remote hardware apply requires a registered remote node and a usable remote `gpum` command path.
- `gpum --version` should report `gpum 1.0.1` for this release.

---

## Known Limits in v1.0.1

Still conservative or partial:

- hard GPU reset remains blocked
- unsafe clock mutation remains blocked
- MIG mode enable/disable is not automated
- AMD and Intel partition lifecycle is still logical-only
- process cleanup is local-node only
- Docker and Kubernetes reconcile commands are read-only
- migration is checkpoint/restore based, not transparent hot memory migration
- NVML and Level Zero support is intentionally basic
- deep NUMA, NIC, RDMA, and storage locality scheduling is not complete
- fair-share queue aging and true cluster preemption are not implemented

---

## Suggested Release Title

```text
gpum v1.0.1
```

## Recommended Release Assets

Attach these files to the GitHub Release:

- `gpu-mgr.jar`
- `gpum.cmd`
- `gpum.ps1`
- `gpum`
- `install.cmd`
- `install-gpum.sh`
- `gpum-version.txt`
- `gpum.example.yaml`
- `gpum-dist.zip`
