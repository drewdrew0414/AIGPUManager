# Overview

## English

`gpum` is a Java 21 based GPU operations CLI and lightweight gRPC control plane for AI training and inference servers.

It exists because GPU server operations are not just about checking `nvidia-smi`. A real team needs to know who is using which accelerator, whether the device is safe to schedule, whether a job is over quota, whether stale processes are leaking VRAM, and whether a completed job released its resources.

Without a tool like this, teams often run into:

- duplicate GPU assignment
- stale or zombie training processes
- hidden VRAM leaks
- untracked thermal, power, and lease-limit risk
- missing audit evidence after incidents
- manual command drift between operators

`gpum` solves those problems with inventory, allocation leases, safety policy checks, guarded hardware operations, runtime cleanup, RBAC approvals, audit logs, and reporting.

## 한국어

`gpum`은 AI 학습/추론 서버에서 GPU 인벤토리, 자원 할당, 실행, 안전 점검, 감사 로그를 한 번에 관리하기 위한 Java 21 기반 CLI 및 경량 gRPC 제어 도구입니다.

이 도구를 만든 이유는 GPU 서버 운영이 단순히 `nvidia-smi`를 보는 일로 끝나지 않기 때문입니다. 실무에서는 누가 어떤 GPU를 쓰는지, 지금 스케줄링해도 안전한지, quota를 넘지 않았는지, 죽은 프로세스가 VRAM을 붙잡고 있지 않은지, 작업이 끝난 뒤 lease가 회수됐는지까지 추적해야 합니다.

이런 도구가 없으면 다음 문제가 자주 생깁니다.

- GPU 중복 할당
- stale/zombie 학습 프로세스
- 숨은 VRAM 누수
- 온도, 전력, lease 한도 초과 누락
- 장애 후 감사 증적 부족
- 운영자마다 다른 수동 명령 실행

`gpum`은 인벤토리, allocation lease, safety policy, guarded hardware operation, runtime cleanup, RBAC approval, audit log, report로 이 문제를 줄입니다.

## Core Capabilities

- NVIDIA, AMD, Intel GPU inventory
- GPU lease lifecycle: request, dry-run, list, info, extend, move, release, reap
- MIG/partition metadata and guarded apply paths
- Topology-aware, best-fit, worst-fit, and backfill scheduling helpers
- CPU/RAM/PID quota plans and RDMA/InfiniBand policy plans
- Docker, Apptainer, Singularity batch job plans
- Jupyter, VS Code tunnel, and SSH interactive session plans
- Runtime worker registry, watchdog, OOM recovery, migration plan, zombie cleanup
- Dataset cache, snapshot metadata, checkpoint movement, GDS readiness plans
- RBAC, approval workflow, audit trail, operational logs
- Prometheus export, profiler plans, alert policies, FinOps reports
- Java 21 virtual-thread gRPC server mode
- Safety guardrails for physical damage prevention and quota overrun prevention

