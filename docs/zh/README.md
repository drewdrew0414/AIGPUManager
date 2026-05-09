# gpum 中文 README

Version: **1.1.0**

## 目录

- [gpum 是什么？](#gpum-是什么)
- [为什么要使用它](#为什么要使用它)
- [如果不用会出现什么问题](#如果不用会出现什么问题)
- [Quick Installation](#quick-installation)
- [Quickstart](#quickstart)
- [工作流如何衔接](#工作流如何衔接)
- [生产环境安全功能](#生产环境安全功能)
- [文档链接](#文档链接)

## gpum 是什么？

`gpum` 是基于 Java 21 的共享 GPU 资源管理器，用于在 GPU 服务器和 GPU 集群上运行 AI 训练、批处理任务和交互式会话。它不仅管理 GPU，还管理 CPU、内存、容器、存储、日志、配额、成本、权限和审计记录。

## 为什么要使用它

共享 GPU 环境不能依赖人工分配。团队需要可重复的调度、配额限制、拓扑感知、实时监控、安全上限、成本统计和审计追踪。`gpum` 将这些能力整合到 CLI 工作流中，研究人员和运维人员都可以使用。

## 如果不用会出现什么问题

- 多个任务可能被分配到同一张 GPU。
- 单个进程可能耗尽主机内存并影响整台服务器。
- PyTorch 任务可能因为容器 `/dev/shm` 太小而失败。
- 任务崩溃后，僵尸进程可能继续占用显存。
- 温度、功耗、ECC、XID 异常可能被忽略。
- 用户、项目和团队的成本归属不清晰。
- 审计日志分散在不同机器上，难以追踪。

`gpum` 通过 [Safety and limits](../safety.md)、[Operating flow](../operating-flow.md) 和 [All CLI command examples](../commands.md) 来降低这些风险。

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

完整安装说明见 [Installation](../installation.md)。

## Quickstart

先扫描资源，再提交训练任务，最后监控任务状态:

```bash
gpum scan --refresh
gpum submit --image registry.example.com/ml/train:1.1.0 --gpu a100:2 --cpu 16 --memory 96g --command "python train.py"
gpum watch job-20260510-001
```

## 工作流如何衔接

1. `gpum scan --refresh` 刷新节点、GPU、MIG、NVLink、温度、功耗和内存状态。
2. `gpum dry-run ...` 在执行前验证配额、RBAC、成本、拓扑和容器选项。
3. `gpum submit ...` 将任务交给调度器，并应用 best-fit、worst-fit、gang scheduling、preemption、backfilling 等策略。
4. `gpum watch <job-id>` 和 `gpum logs <job-id> --follow` 展示实时状态和日志。
5. `gpum report cost ...` 和 `gpum audit ...` 保存成本和审计记录。

更多说明见 [Operating flow](../operating-flow.md)。

## 生产环境安全功能

- 监控温度、功耗、ECC、XID，并在超过阈值时停止或隔离任务。
- 使用 CPU、内存和 cgroup 限制，避免单个任务影响整台服务器。
- 在启动前验证容器 `/dev/shm`、GPU pass-through、只读数据集挂载和 checkpoint 策略。
- 检测僵尸进程并执行受控 GPU 清理。
- 使用 PostgreSQL 审计日志和 Redis 分布式锁减少重复分配和竞态条件。

完整策略见 [Safety and limits](../safety.md)。

## 文档链接

- [Root README](../../README.md)
- [Documentation index](../README.md)
- [User guide](../USER_GUIDE.md)
- [Overview](../overview.md)
- [Installation](../installation.md)
- [Operating flow](../operating-flow.md)
- [Safety and limits](../safety.md)
- [All CLI command examples](../commands.md)
- [Operational recipes](../recipes.md)
- [Production operations](../operations.md)
- [Release notes](../../README.r.md)
