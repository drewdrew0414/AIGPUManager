# gpum

Version: **1.1.0**

`gpum` is a Java 21 based GPU resource manager for teams that run AI training, batch jobs, and interactive GPU sessions on shared servers. It combines GPU allocation, quota control, scheduling, safety guardrails, observability, and CLI workflows into one operational tool.

## Table of Contents

- [한국어](#한국어)
- [English](#english)
- [中文](#中文)
- [日本語](#日本語)
- [Documentation](#documentation)

## 한국어

### gpum이란?

`gpum`은 여러 사용자가 같은 GPU 서버를 사용할 때 GPU, CPU, RAM, 저장소, 실행 환경을 안전하게 배분하기 위한 리소스 매니저입니다. 단순히 빈 GPU를 보여주는 도구가 아니라, 토폴로지 인식 스케줄링, 쿼타, 선점, 체크포인트, 로그 스트리밍, 비용 추적까지 운영 흐름 전체를 다룹니다.

### 왜 써야 하나?

공유 GPU 서버에서는 한 작업이 모든 메모리를 점유하거나, 발열과 전력 한도를 무시하거나, 좀비 프로세스가 GPU를 붙잡는 일이 자주 발생합니다. `gpum`은 제출 전 검증, 한도 초과 차단, 안전한 컨테이너 실행, 실시간 모니터링, 감사 로그를 통해 이런 문제를 운영 단계에서 줄이도록 설계되었습니다.

### Quick Installation

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

### Quickstart

```bash
gpum scan --refresh
gpum submit --image registry.example.com/ml/train:1.1.0 --gpu a100:2 --cpu 16 --memory 96g --command "python train.py"
gpum watch job-20260510-001
```

전체 한국어 문서는 [docs/ko/README.md](docs/ko/README.md)에서 확인하세요.

## English

### What is gpum?

`gpum` is a resource manager for shared GPU infrastructure. It controls GPU, CPU, memory, storage, container execution, safety limits, and operational visibility for AI training and batch workloads.

### Why use it?

Without a resource manager, users can overrun memory, collide on the same device, leave stale GPU processes, miss thermal or power limits, and lose the operational trail required for teams. `gpum` adds preflight validation, quota enforcement, topology-aware placement, safe execution, live telemetry, cost accounting, and audit logs.

### Quick Installation

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

### Quickstart

```bash
gpum scan --refresh
gpum submit --image registry.example.com/ml/train:1.1.0 --gpu a100:2 --cpu 16 --memory 96g --command "python train.py"
gpum watch job-20260510-001
```

Read the full English documentation at [docs/en/README.md](docs/en/README.md).

## 中文

### gpum 是什么？

`gpum` 是面向共享 GPU 集群的资源管理工具。它负责 GPU、CPU、内存、存储、容器执行、安全限制和运行可观测性，适用于 AI 训练、批处理任务和交互式会话。

### 为什么需要它？

如果没有统一管理，任务可能会抢占全部显存或内存，重复分配同一张 GPU，留下僵尸进程，忽略温度和功耗限制，并且缺少审计记录。`gpum` 通过预检、配额、拓扑感知调度、安全执行、实时监控、成本统计和审计日志降低这些风险。

### Quick Installation

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

### Quickstart

```bash
gpum scan --refresh
gpum submit --image registry.example.com/ml/train:1.1.0 --gpu a100:2 --cpu 16 --memory 96g --command "python train.py"
gpum watch job-20260510-001
```

完整中文文档见 [docs/zh/README.md](docs/zh/README.md)。

## 日本語

### gpum とは？

`gpum` は共有 GPU 環境向けのリソースマネージャーです。GPU、CPU、メモリ、ストレージ、コンテナ実行、安全制限、監視、監査ログをまとめて扱い、AI 学習やバッチジョブを安全に実行できるようにします。

### なぜ使うのか？

統一された管理がない環境では、メモリの使い切り、GPU の重複利用、残留プロセス、温度や電力制限の見落とし、監査不能な実行が起きやすくなります。`gpum` は事前検証、クォータ、トポロジー認識スケジューリング、安全な実行、リアルタイム監視、コスト集計でそれらを抑えます。

### Quick Installation

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

### Quickstart

```bash
gpum scan --refresh
gpum submit --image registry.example.com/ml/train:1.1.0 --gpu a100:2 --cpu 16 --memory 96g --command "python train.py"
gpum watch job-20260510-001
```

日本語ドキュメントは [docs/ja/README.md](docs/ja/README.md) を参照してください。

## Documentation

- [Korean README](docs/ko/README.md)
- [English README](docs/en/README.md)
- [Chinese README](docs/zh/README.md)
- [Japanese README](docs/ja/README.md)
- [Documentation index](docs/README.md)
- [User guide](docs/USER_GUIDE.md)
- [Overview](docs/overview.md)
- [Installation](docs/installation.md)
- [Operating flow](docs/operating-flow.md)
- [Safety and limits](docs/safety.md)
- [Fleet intelligence](docs/fleet.md)
- [All CLI command examples](docs/commands.md)
- [Operational recipes](docs/recipes.md)
- [Production operations](docs/operations.md)
- [Release notes](README.r.md)
