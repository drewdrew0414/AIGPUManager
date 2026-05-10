# Overview

Version: **1.1.0**

## Table of Contents

- [한국어](#한국어)
- [English](#english)
- [中文](#中文)
- [日本語](#日本語)
- [Architecture Map](#architecture-map)
- [Related Documents](#related-documents)

## 한국어

`gpum`은 공유 GPU 서버와 GPU 클러스터를 운영하기 위한 Java 21 기반 CLI 및 경량 제어 계층입니다. 단순 인벤토리 도구가 아니라 GPU 할당, CPU/RAM 한도, 스케줄링, 데이터 캐싱, 컨테이너 실행, 런타임 감시, 안전 정책, 비용 추적, RBAC, 감사 로그를 하나의 운영 흐름으로 묶습니다.

핵심 목표는 세 가지입니다.

1. 같은 GPU나 노드가 중복 할당되지 않게 막습니다.
2. 온도, 전력, VRAM, `/dev/shm`, lease, 쿼타 초과를 제출 전과 실행 중에 감지합니다.
3. 누가 어떤 자원을 사용했고 어떤 정책으로 허용됐는지 감사 가능한 기록으로 남깁니다.

`fleet` 계층은 이 위에 추가된 운영 분석 기능입니다. [Fleet intelligence](fleet.md)를 통해 실제 스케줄 가능한 용량, 위험도, 제출 전 검증, GPU-hour 예측, 일일 doctor 리포트를 확인할 수 있습니다.

## English

`gpum` is a Java 21 based CLI and lightweight control layer for shared GPU servers and GPU clusters. It is more than inventory: it connects GPU allocation, CPU/RAM limits, scheduling, data caching, container execution, runtime supervision, safety policy, cost tracking, RBAC, and audit trails.

The main goals are:

1. Prevent duplicate GPU or node allocation.
2. Detect thermal, power, VRAM, `/dev/shm`, lease, and quota risks before and during execution.
3. Preserve an auditable record of who used which resources and which policy allowed the action.

The `fleet` layer adds production analysis on top of that. See [Fleet intelligence](fleet.md) for schedulable capacity, risk analysis, pre-submit validation, GPU-hour forecasting, and daily doctor reports.

## 中文

`gpum` 是基于 Java 21 的共享 GPU 服务器和 GPU 集群运维 CLI。它不仅做 inventory，还把 GPU 分配、CPU/RAM 限制、调度、数据缓存、容器执行、运行时监控、安全策略、成本追踪、RBAC 和审计日志连接成统一流程。

主要目标:

1. 防止 GPU 或节点重复分配。
2. 在提交前和运行中检测温度、功耗、显存、`/dev/shm`、lease 和配额风险。
3. 保存可审计记录，说明谁使用了什么资源，以及由什么策略允许。

新增的 `fleet` 层提供生产环境分析。请查看 [Fleet intelligence](fleet.md)，了解可调度容量、风险分析、提交前验证、GPU-hour 预测和 doctor 报告。

## 日本語

`gpum` は Java 21 ベースの共有 GPU サーバーおよび GPU クラスター向け CLI です。単なる inventory ではなく、GPU 割り当て、CPU/RAM 制限、スケジューリング、データキャッシュ、コンテナ実行、ランタイム監視、安全ポリシー、コスト追跡、RBAC、監査ログを一つの運用フローにまとめます。

主な目的:

1. GPU やノードの重複割り当てを防ぎます。
2. 温度、電力、VRAM、`/dev/shm`、lease、クォータのリスクを投入前と実行中に検出します。
3. 誰がどのリソースを使い、どのポリシーで許可されたかを監査可能にします。

`fleet` レイヤーは本番運用分析を追加します。[Fleet intelligence](fleet.md) でスケジュール可能容量、リスク分析、投入前検証、GPU-hour 予測、doctor レポートを確認できます。

## Architecture Map

- CLI: picocli command surface with command groups for inventory, allocation, fleet analysis, scheduling, runtime, data, reports, RBAC, and system operations.
- Runtime: Java 21 services with conservative dry-run-first execution for external tools.
- Persistence: SQLite by default, with PostgreSQL and Redis readiness paths for server deployments.
- Control plane: lightweight gRPC server/client commands for centralized allocation, heartbeat, telemetry, release, and storage checks.
- Safety model: hardware mutation requires explicit flags, RBAC, approval records, and environment opt-in.
- Fleet analysis: read-only capacity, risk, validation, forecast, and doctor reports that summarize the whole system before execution.

## Related Documents

- [Root README](../README.md)
- [Documentation index](README.md)
- [Installation](installation.md)
- [Operating flow](operating-flow.md)
- [Safety and limits](safety.md)
- [Fleet intelligence](fleet.md)
- [All CLI command examples](commands.md)
- [Operational recipes](recipes.md)
- [Production operations](operations.md)
- [Release notes](../README.r.md)
