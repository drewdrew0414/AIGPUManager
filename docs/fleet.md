# Fleet Intelligence

Version: **1.1.0**

`gpum fleet` is a read-only production intelligence layer. It does not mutate hardware, kill processes, or submit jobs. It combines inventory, active allocations, runtime workers, node attributes, health scores, and safety policies into reports that operators can use before allocating or running workloads.

## Table of Contents

- [한국어](#한국어)
- [English](#english)
- [中文](#中文)
- [日本語](#日本語)
- [Command Examples](#command-examples)
- [Exception and Error Hardening](#exception-and-error-hardening)
- [How It Fits the Workflow](#how-it-fits-the-workflow)

## 한국어

### 무엇이 추가됐나?

`fleet` 명령은 개별 명령으로 흩어져 있던 운영 정보를 하나로 합칩니다. GPU 총량만 보는 것이 아니라, 실제 스케줄 가능한 GPU, 이미 할당된 GPU, quarantine된 GPU, VRAM 여유, 전력 여유, 토폴로지, 라벨, 노드 차단 상태, 런타임 worker 이상 징후, 안전 정책 누락 여부까지 함께 봅니다.

### 핵심 기능

- `fleet capacity`: 노드별/모델별 사용 가능 GPU, VRAM, 전력, 온도, 라벨, 차단 상태 리포트.
- `fleet risk`: stale scan, thermal/power/VRAM 위험, expired allocation, blocked node allocation, runtime restart, 정책 누락을 한 번에 점검.
- `fleet validate`: 실제 제출 전 GPU, VRAM, CPU, RAM, `/dev/shm`, 이미지 태그, label selector, packed/spread 배치 가능성을 검증.
- `fleet forecast`: free GPU-hour 기준으로 일별 처리 가능 job 수와 과점유 여부를 예측.
- `fleet doctor`: capacity, risk, forecast를 묶은 운영 readiness 리포트.

### 왜 필요한가?

실무에서는 `gpu list`만 보고 작업을 넣으면 부족합니다. GPU가 보여도 노드가 drain 상태일 수 있고, GPU가 free처럼 보여도 quarantine 라벨이 붙어 있을 수 있으며, `/dev/shm` 또는 팀 쿼타 때문에 실제 실행은 실패할 수 있습니다. `fleet validate`는 이런 조건을 제출 전에 막고, `fleet risk`는 운영자가 놓치기 쉬운 경고를 표준화합니다.

## English

### What changed?

`fleet` adds a read-only intelligence layer above the existing inventory, allocation, scheduling, safety, and runtime commands. It reports what is actually schedulable rather than only what exists physically.

### Core features

- `fleet capacity`: node and model capacity with free GPUs, allocated GPUs, quarantined GPUs, VRAM, power, topology, labels, and blockers.
- `fleet risk`: stale inventory, thermal risk, power pressure, VRAM pressure, expired allocations, blocked-node allocations, runtime restart risk, and missing production policies.
- `fleet validate`: pre-submit validation for GPU count, VRAM, CPU, RAM, `/dev/shm`, image pinning, label selectors, and packed/spread placement.
- `fleet forecast`: GPU-hour runway and maximum daily job throughput from current free capacity.
- `fleet doctor`: combined readiness report for operators.

### Why it matters

Production GPU operations need more than raw device discovery. A visible GPU may be unschedulable because the node is drained, quarantined, stale, or already committed. `fleet` makes that difference explicit before the user submits a job.

## 中文

### 新增内容

`fleet` 是只读的生产环境分析层。它把 inventory、allocation、runtime worker、node attribute、health score 和 safety policy 汇总到一个报告中，帮助运维人员在提交任务前判断是否安全。

### 核心功能

- `fleet capacity`: 按节点和模型展示可调度 GPU、已分配 GPU、隔离 GPU、VRAM、电力、拓扑、标签和阻塞状态。
- `fleet risk`: 检查过期扫描、温度、电力、显存、过期 allocation、阻塞节点上的任务、runtime restart 和策略缺失。
- `fleet validate`: 在提交前验证 GPU、VRAM、CPU、RAM、`/dev/shm`、镜像标签、label selector 和 packed/spread 放置方式。
- `fleet forecast`: 根据当前空闲 GPU-hour 预测每日可运行任务数。
- `fleet doctor`: 面向运维的综合 readiness 报告。

## 日本語

### 追加内容

`fleet` は読み取り専用の本番運用分析レイヤーです。inventory、allocation、runtime worker、node attribute、health score、safety policy をまとめ、ジョブ投入前に安全性と容量を確認できます。

### 主な機能

- `fleet capacity`: ノード別/モデル別のスケジュール可能 GPU、割り当て済み GPU、隔離 GPU、VRAM、電力、トポロジー、ラベル、ブロッカーを表示。
- `fleet risk`: stale scan、温度、電力、VRAM、期限切れ allocation、blocked node allocation、runtime restart、ポリシー未設定を確認。
- `fleet validate`: GPU、VRAM、CPU、RAM、`/dev/shm`、イメージタグ、label selector、packed/spread 配置を事前検証。
- `fleet forecast`: 現在の空き GPU-hour から日次処理可能ジョブ数を予測。
- `fleet doctor`: 運用 readiness をまとめて表示。

## Command Examples

All examples use dummy values.

```bash
gpum fleet capacity
gpum fleet capacity --by-model

gpum fleet risk --max-scan-age-min 30 --min-free-vram-ratio 0.05
gpum fleet risk --max-scan-age-min 15 --fail-on critical
gpum fleet risk --max-scan-age-min 15 --fail-on warn

gpum fleet validate --gpus 2 --vram 80000 --hours 6 --cpu-cores 16 --memory-mb 131072 --shm-size 64g --model H100 --label-selector role=trainer --strategy packed --image registry.example.com/ml/train:1.1.0 --command "python train.py"
gpum fleet validate --gpus 8 --vram 80000 --hours 12 --strategy spread --image registry.example.com/ml/ddp:1.1.0 --command "torchrun --nproc_per_node=8 train.py" --fail-on-warn

gpum fleet forecast --days 14 --target-utilization 0.70 --reserve-ratio 0.20 --job-gpu-hours 32 --jobs-per-day 6
gpum fleet doctor --max-scan-age-min 30
gpum fleet doctor --max-scan-age-min 15 --fail-on-critical
```

## Exception and Error Hardening

`fleet` now treats malformed or risky state as reportable findings instead of letting the analysis crash whenever possible.

- Malformed `system safety policy` metadata falls back to safe defaults and emits a governance warning.
- Invalid `label-selector` syntax is returned as a `BLOCK` validation check instead of an uncaught exception.
- Duplicate GPU UUIDs, duplicate node/device slots, and multiple active allocation claims on one GPU are reported as critical findings.
- Active allocations that reference GPUs no longer present in inventory are reported as critical findings.
- Implausible telemetry such as free VRAM greater than total VRAM or impossible temperatures is reported.
- Missing driver versions and missing GPU UUIDs are reported as inventory quality warnings.
- Commands containing GPU reset, destructive filesystem formatting/deletion patterns, privilege escalation, or remote shell piping are flagged during `fleet validate`.
- Mutable or unpinned container image references are flagged before submission.
- Runtime workers that reference missing allocations are reported as critical runtime findings.
- Queue, reservation, telemetry, and budget records are checked for invalid values, impossible GPU requests, stale reservations, noisy polling, and over-budget state.

## How It Fits the Workflow

1. Run [node scan](commands.md#node) to refresh hardware inventory.
2. Run [system safety policy](commands.md#system-and-safety) to store explicit safety limits.
3. Run `gpum fleet capacity --by-model` to confirm actual schedulable capacity.
4. Run `gpum fleet risk --fail-on critical` before a maintenance window or large job.
5. Run `gpum fleet validate ...` with the same values that will be used for allocation or job submission.
6. Submit the workload through [Allocation](commands.md#allocation), [Job](commands.md#job), or [Server](commands.md#server) commands.
7. Use `gpum fleet doctor` during handoff, incident review, or daily operations.
