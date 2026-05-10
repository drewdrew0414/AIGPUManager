# gpum Documentation

Version: **1.1.0**

This page is the multilingual documentation hub for `gpum`.

## Table of Contents

- [Language README Files](#language-readme-files)
- [Topic Documents](#topic-documents)
- [한국어 안내](#한국어-안내)
- [English Guide](#english-guide)
- [中文指南](#中文指南)
- [日本語ガイド](#日本語ガイド)

## Language README Files

- [한국어 README](ko/README.md)
- [English README](en/README.md)
- [中文 README](zh/README.md)
- [日本語 README](ja/README.md)

## Topic Documents

- [Overview](overview.md): architecture, resource model, scheduling model, safety model.
- [Installation](installation.md): Windows, Linux, macOS installation flow.
- [Operating flow](operating-flow.md): how scan, safety, allocation, execution, monitoring, and reporting connect.
- [Safety and limits](safety.md): thermal, power, memory, quota, fault, and exception prevention.
- [Fleet intelligence](fleet.md): deep capacity, risk, validation, forecast, and doctor reports.
- [All CLI command examples](commands.md): command examples with dummy values.
- [Operational recipes](recipes.md): practical workflows for teams, reservations, jobs, and monitoring.
- [Production operations](operations.md): production runbook, rollback, audit, and incident handling.
- [Release notes](../README.r.md): version 1.1.0 release details.
- [Root README](../README.md): four-language quick entry page.

## 한국어 안내

한국어 사용자는 [한국어 README](ko/README.md)에서 시작하세요. 30초 Quickstart, 설치, 운영 흐름, 안전 정책, 전체 CLI 예시 링크가 포함되어 있습니다.

주요 흐름:

1. [설치](installation.md) 후 `gpum --version`으로 1.1.0을 확인합니다.
2. [운영 흐름](operating-flow.md)에 따라 `scan -> submit -> watch -> report` 순서로 사용합니다.
3. [안전 정책](safety.md)과 [명령어 예시](commands.md)를 참고해 쿼타, 온도, 전력, 메모리 한도를 검증합니다.

## English Guide

English users should start with the [English README](en/README.md). It includes quick installation, a three-command quickstart, workflow explanation, safety policy links, and the full CLI reference.

Recommended path:

1. Install from [Installation](installation.md) and verify `gpum --version`.
2. Follow [Operating flow](operating-flow.md) for `scan -> submit -> watch -> report`.
3. Use [Safety and limits](safety.md) and [All CLI command examples](commands.md) before production use.

## 中文指南

中文用户请从 [中文 README](zh/README.md) 开始。该文档包含快速安装、三行 Quickstart、运行流程、安全策略以及完整 CLI 示例链接。

建议路径:

1. 根据 [Installation](installation.md) 安装并确认 `gpum --version` 为 1.1.0。
2. 按 [Operating flow](operating-flow.md) 使用 `scan -> submit -> watch -> report`。
3. 在生产环境使用前阅读 [Safety and limits](safety.md) 与 [All CLI command examples](commands.md)。

## 日本語ガイド

日本語ユーザーは [日本語 README](ja/README.md) から始めてください。クイックインストール、3 コマンドの Quickstart、運用フロー、安全ポリシー、CLI 例へのリンクを含みます。

推奨フロー:

1. [Installation](installation.md) に従ってインストールし、`gpum --version` が 1.1.0 であることを確認します。
2. [Operating flow](operating-flow.md) に沿って `scan -> submit -> watch -> report` を実行します。
3. 本番利用前に [Safety and limits](safety.md) と [All CLI command examples](commands.md) を確認します。
