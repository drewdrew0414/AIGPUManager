# gpum v1.1.0

Production operations release of `gpum`, a multi-vendor GPU inventory, allocation, governance, runtime, scheduling, data, and developer-experience CLI for AI training and inference servers.

## Release Summary

`v1.1.0` adds the practical platform features needed to operate GPU clusters beyond simple inventory and allocation. It introduces production-oriented command groups for compute policy, multi-level scheduling, data movement, batch jobs, interactive sessions, alert policies, secret references, and developer tooling.

The implementation remains conservative: potentially destructive or environment-specific actions are preview-first and require `--execute` before external commands run.

---

## New in v1.1.0

### 1. Compute Policy Layer

New command group:

- `gpum compute quota`
- `gpum compute rdma`
- `gpum compute accelerator register`
- `gpum compute accelerator list`

Included behavior:

- CPU/RAM/PID quota plans for allocation-scoped workloads
- Linux cgroup command plans using `cgcreate`, `cgset`, and `cgclassify`
- Windows guidance path for Job Objects or container limits
- RDMA/InfiniBand traffic-control policy plans
- generic accelerator registry for NPU, TPU, LPU, FPGA, and custom endpoints

### 2. Production Scheduling Layer

New command group:

- `gpum schedule queue create`
- `gpum schedule queue list`
- `gpum schedule reserve create`
- `gpum schedule reserve list`
- `gpum schedule reserve cancel`
- `gpum schedule fair-share`
- `gpum schedule gang`

Included behavior:

- tenant/project queue records with weights and max GPU caps
- advance reservations with ISO-8601 start/end windows
- fair-share scoring based on historical allocation GPU-hours
- gang scheduling plans for distributed jobs that need all nodes before start
- preemption plans for suspending lower-priority allocations and recording resume commands
- best-fit, worst-fit, and topology-aware bin-packing simulation
- backfill planning for short idle windows

### 3. Data Management Layer

New command group:

- `gpum data cache`
- `gpum data snapshot`
- `gpum data checkpoint`

Included behavior:

- local NVMe cache sync plans for file paths, NFS-style paths, or `s3://` sources
- immutable dataset snapshot records for reproducibility
- checkpoint push plans for local or object-storage destinations
- GPU Direct Storage readiness plans
- dry-run default with external sync only under `--execute`

### 4. Job and Session Orchestration

New command group:

- `gpum job batch`
- `gpum job session`
- `gpum job list`

Included behavior:

- batch job records and optional runtime worker submission
- Docker, Apptainer, and Singularity execution plans
- Docker GPU pass-through and shared-memory sizing options
- interactive session plans for Jupyter, VS Code tunnel, and SSH
- allocation-linked job metadata
- zombie GPU process discovery and cleanup preview/execute path

### 5. Observability Extensions

New command group:

- `gpum observe alert create`
- `gpum observe alert list`
- `gpum observe profile`

Included behavior:

- Slack, Teams, Email, and webhook alert policy records
- telemetry collection policies and current Prometheus snapshot writes
- centralized SQLite log streaming
- profiler wrapper plans for NVIDIA Nsight Systems (`nsys`) and Nsight Compute (`ncu`)
- shell profiler fallback for generic commands

### 6. Secret References

New command group:

- `gpum secret put`
- `gpum secret list`
- `gpum secret render`

`gpum` stores secret references, not raw secret values by default. Supported reference providers include Vault, Kubernetes secrets, environment variables, AWS, GCP, and Azure.

### 7. Developer Experience

New command group:

- `gpum dev completion`
- `gpum dev native`
- `gpum dev python-sdk`

Included behavior:

- shell completion packaging hints
- GraalVM native-image build plan
- JLine3 terminal integration check
- generated lightweight Python SDK wrapper for invoking `gpum`

### 8. Java Server Architecture

New command group:

- `gpum server run`
- `gpum server health`
- `gpum server resources`
- `gpum server allocate`
- `gpum server release`
- `gpum server submit`
- `gpum server heartbeat`
- `gpum server telemetry`
- `gpum server storage`

Included behavior:

- Java 21 virtual-thread-backed gRPC server runtime
- `gpum.v1.GpumControl` protocol specification in `src/main/proto/gpum.proto`
- health endpoint for CLI/server communication checks
- resource summary endpoint for node/GPU/allocation/ops-record counts
- allocation planning endpoint for remote dry-run style planning
- server-side allocation lease creation with tenant, owner, model, VRAM, label selector, priority, preemptible, exclusive-node, and affinity fields
- allocation release endpoint for centralized lease cleanup
- remote batch job submission plans bound to allocation IDs, including Docker/Apptainer/Singularity engine, GPU pass-through, and shared-memory sizing
- node heartbeat endpoint for worker/node liveness and allocatable GPU reporting
- bidirectional telemetry stream endpoint
- optional PostgreSQL and Redis backend readiness checks
- Redis distributed lock acquire/release helper for race-condition prevention

Backend environment variables:

- `GPUM_POSTGRES_URL`
- `GPUM_POSTGRES_USER`
- `GPUM_POSTGRES_PASSWORD`
- `GPUM_REDIS_URL`

SQLite remains the default local persistence path. PostgreSQL and Redis support is introduced as server-mode readiness and integration plumbing for production deployments.

### 9. Unified Ops Persistence

New SQLite-backed operational record store:

- compute quota and RDMA policies
- model-specific GPU quotas such as tenant/model/maxGpus
- accelerator registry entries
- scheduling queues and reservations
- gang scheduling plans
- dataset cache, snapshot, and checkpoint records
- batch jobs and sessions
- alert policies and profile runs
- telemetry policies and budget alerts
- job cost estimates from GPU model, duration, count, and hourly rate
- secret references

All records are stored in `ops_records`.

### 10. Safety Guardrails

New command group:

- `gpum system safety limits`
- `gpum system safety policy`
- `gpum system safety check`
- `gpum system safety incident`

Included behavior:

- cluster-wide safety policy for max GPUs per request, max lease hours, thermal thresholds, power cap ceiling, minimum free VRAM ratio, disk headroom, stale heartbeat seconds, and max job shared-memory size
- allocation and remote server allocation checks against the active safety policy
- batch job engine and shared-memory validation before execution plans are recorded
- dependent execution commands validate referenced allocations before recording or executing job, session, profile, quota, and preemption work
- compute limits reject runaway CPU, memory, PID, RDMA bandwidth, and model quota values before persistence
- parse and execution failures use consistent `ERROR` and `HINT` output
- preflight detection for thermal critical GPUs, power saturation, stale heartbeats, expired active leases, low metadata disk space, and hardware-write opt-in left enabled
- incident records with optional GPU quarantine or node drain actions

---

## Included v1.1.0 Hardware and Runtime Highlights

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

Partition records remain cross-vendor, and guarded NVIDIA MIG apply support is available where the device already advertises MIG capability.

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

## v1.1.0 Baseline Features Retained

`v1.1.0` keeps the original public surface:

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

## Version Alignment

All release-facing version identifiers are aligned to `v1.1.0`:

- Gradle project version: `1.1.0`
- CLI version output: `gpum 1.1.0`
- generated distribution version file: `1.1.0`
- release note title: `gpum v1.1.0`
- README release summary: `v1.1.0`

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
- `gpum --version` should report `gpum 1.1.0` for this release.

---

## Known Limits in v1.1.0

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
gpum v1.1.0
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
