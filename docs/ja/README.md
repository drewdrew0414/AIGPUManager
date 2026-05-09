# gpum 日本語 README

Version: **1.1.0**

## 目次

- [gpum とは？](#gpum-とは)
- [なぜ作られたのか](#なぜ作られたのか)
- [使わない場合に起きる問題](#使わない場合に起きる問題)
- [Quick Installation](#quick-installation)
- [Quickstart](#quickstart)
- [ワークフローのつながり](#ワークフローのつながり)
- [本番運用向け安全機能](#本番運用向け安全機能)
- [ドキュメントリンク](#ドキュメントリンク)

## gpum とは？

`gpum` は Java 21 ベースの共有 GPU リソースマネージャーです。GPU サーバーや GPU クラスター上で、AI 学習、バッチジョブ、インタラクティブセッションを安全に実行するために、GPU、CPU、メモリ、コンテナ、ストレージ、ログ、クォータ、コスト、権限、監査ログをまとめて管理します。

## なぜ作られたのか

共有 GPU 環境を手作業で運用すると、割り当ての衝突、リソースの使い切り、発熱、電力超過、障害の波及、再現性の低下が起きやすくなります。`gpum` はジョブ投入前にリソース、クォータ、トポロジー、予想コスト、安全上限を検証し、実行中はリアルタイムのテレメトリとログを提供します。

## 使わない場合に起きる問題

- 複数のジョブが同じ GPU を使い、失敗や性能低下が起きます。
- 1 つのプロセスがホストメモリを使い切る可能性があります。
- コンテナの `/dev/shm` 不足で PyTorch の DataLoader が失敗します。
- ジョブ失敗後に残留プロセスが GPU メモリを保持します。
- 温度、電力、ECC、XID などの異常を見逃します。
- ユーザーやプロジェクト単位のコストが追跡しにくくなります。
- 監査ログが不足し、実行履歴を説明できません。

`gpum` は [Safety and limits](../safety.md)、[Operating flow](../operating-flow.md)、[All CLI command examples](../commands.md) でこれらのリスクを扱います。

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

詳しい手順は [Installation](../installation.md) を参照してください。

## Quickstart

リソースをスキャンし、学習ジョブを投入して、状態を監視します。

```bash
gpum scan --refresh
gpum submit --image registry.example.com/ml/train:1.1.0 --gpu a100:2 --cpu 16 --memory 96g --command "python train.py"
gpum watch job-20260510-001
```

## ワークフローのつながり

1. `gpum scan --refresh` でノード、GPU、MIG、NVLink、温度、電力、メモリ状態を更新します。
2. `gpum dry-run ...` で実行前にクォータ、RBAC、コスト、トポロジー、コンテナ設定を検証します。
3. `gpum submit ...` でジョブを投入し、スケジューラーが best-fit、worst-fit、gang scheduling、preemption、backfilling を適用します。
4. `gpum watch <job-id>` と `gpum logs <job-id> --follow` で状態とログを確認します。
5. `gpum report cost ...` と `gpum audit ...` でコストと監査記録を保存します。

詳細は [Operating flow](../operating-flow.md) を参照してください。

## 本番運用向け安全機能

- 温度、電力、ECC、XID を監視し、閾値超過時にジョブ停止や隔離を行います。
- CPU、RAM、cgroup の上限を適用し、1 つのジョブがノード全体を不安定にすることを防ぎます。
- コンテナ `/dev/shm`、GPU pass-through、読み取り専用データセットマウント、checkpoint ポリシーを起動前に検証します。
- 残留プロセスを検出し、制御された GPU クリーンアップを行います。
- PostgreSQL の監査ログと Redis ベースのロックで重複割り当てや競合を減らします。

詳細は [Safety and limits](../safety.md) を確認してください。

## ドキュメントリンク

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
