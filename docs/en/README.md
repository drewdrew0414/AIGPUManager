# gpum English README

Version: **1.1.0**

## Table of Contents

- [What is gpum?](#what-is-gpum)
- [Why it exists](#why-it-exists)
- [What breaks without it](#what-breaks-without-it)
- [Quick Installation](#quick-installation)
- [Quickstart](#quickstart)
- [How the workflow connects](#how-the-workflow-connects)
- [Production safety features](#production-safety-features)
- [Documentation Links](#documentation-links)

## What is gpum?

`gpum` is a Java 21 based resource manager for shared GPU servers and GPU clusters. It manages AI training jobs, batch jobs, and interactive sessions across GPU, CPU, memory, containers, storage, logs, quotas, cost tracking, RBAC, and audit trails.

## Why it exists

Shared GPU infrastructure fails operationally when allocation is manual. Teams need deterministic placement, quota enforcement, topology-aware scheduling, safety limits, live telemetry, and an auditable execution trail. `gpum` puts those controls behind a CLI workflow that can be used by researchers and operators.

## What breaks without it

- Multiple jobs may collide on the same GPU.
- A single process can consume host memory and destabilize the node.
- PyTorch jobs can fail because container shared memory is too small.
- Zombie processes can keep GPU memory allocated after a failed run.
- Thermal, power, ECC, or XID events can be missed until hardware or jobs are affected.
- Cost and ownership become unclear across users and projects.
- Audit records are incomplete or scattered across machines.

`gpum` addresses these risks through [Safety and limits](../safety.md), [Operating flow](../operating-flow.md), and [All CLI command examples](../commands.md).

## Quick Installation

Windows:

```powershell
.\gradlew.bat installDist
.\build\install\gpum\bin\gpum.bat --version
```

Linux:

```bash
./gradlew installDist
./build/install/gpum/bin/gpum --version
```

macOS:

```bash
./gradlew installDist
./build/install/gpum/bin/gpum --version
```

See [Installation](../installation.md) for the complete setup path.

## Quickstart

Run a scan, submit a training job, then watch it:

```bash
gpum scan --refresh
gpum submit --image registry.example.com/ml/train:1.1.0 --gpu a100:2 --cpu 16 --memory 96g --command "python train.py"
gpum watch job-20260510-001
```

## How the workflow connects

1. `gpum scan --refresh` refreshes node, GPU, MIG, NVLink, temperature, power, and memory state.
2. `gpum dry-run ...` validates quota, RBAC, cost, topology, and container options before execution.
3. `gpum submit ...` sends the job to a scheduler that can apply best-fit, worst-fit, gang scheduling, preemption, and backfilling policies.
4. `gpum watch <job-id>` and `gpum logs <job-id> --follow` show live status and logs.
5. `gpum report cost ...` and `gpum audit ...` preserve cost and audit records.

Read [Operating flow](../operating-flow.md) for the detailed sequence.

## Production safety features

- Temperature, power, ECC, and XID monitoring with configurable stop and quarantine behavior.
- CPU, RAM, and cgroup limits to stop one job from destabilizing a node.
- Container `/dev/shm`, GPU pass-through, read-only dataset mounts, and checkpoint policies validated before launch.
- Zombie process detection and controlled GPU cleanup.
- PostgreSQL audit logs and Redis-backed locks to reduce duplicate allocation races.

See [Safety and limits](../safety.md) for full details.

## Documentation Links

- [Root README](../../README.md)
- [Documentation index](../README.md)
- [User guide](../USER_GUIDE.md)
- [Overview](../overview.md)
- [Installation](../installation.md)
- [Operating flow](../operating-flow.md)
- [Safety and limits](../safety.md)
- [Fleet intelligence](../fleet.md)
- [All CLI command examples](../commands.md)
- [Operational recipes](../recipes.md)
- [Production operations](../operations.md)
- [Release notes](../../README.r.md)
