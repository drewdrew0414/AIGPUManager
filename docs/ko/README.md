# gpum 한국어 README

Version: **1.1.0**

## 목차

- [gpum이란?](#gpum이란)
- [왜 만들었나](#왜-만들었나)
- [이걸 안 쓰면 생기는 문제](#이걸-안-쓰면-생기는-문제)
- [Quick Installation](#quick-installation)
- [Quickstart](#quickstart)
- [운영 흐름](#운영-흐름)
- [실무 안전 기능](#실무-안전-기능)
- [문서 링크](#문서-링크)

## gpum이란?

`gpum`은 공유 GPU 서버와 GPU 클러스터에서 AI 학습, 배치 작업, 대화형 세션을 안전하게 실행하기 위한 Java 21 기반 리소스 매니저입니다. GPU만 보는 도구가 아니라 CPU, RAM, 컨테이너, 저장소, 로그, 비용, 권한, 감사 로그까지 하나의 흐름으로 관리합니다.

## 왜 만들었나

실무의 GPU 서버는 한 사람이 단독으로 쓰는 장비가 아닙니다. 여러 연구원과 서비스가 같은 노드를 사용하면 자원 충돌, 과점유, 발열, 전력 초과, 장애 전파, 결과 재현성 문제가 동시에 발생합니다. `gpum`은 작업 제출 전에 자원 가용성, 쿼타, 토폴로지, 예상 비용, 안전 한도를 검증하고 실행 중에는 실시간 텔레메트리와 로그를 제공합니다.

## 이걸 안 쓰면 생기는 문제

- 같은 GPU가 중복 할당되어 작업이 실패하거나 성능이 급락합니다.
- PyTorch DataLoader가 `/dev/shm` 부족으로 실패합니다.
- 좀비 프로세스가 GPU 메모리를 점유한 채 남습니다.
- 온도, 전력, ECC/XID 오류를 놓쳐 물리적 손상 위험이 커집니다.
- 팀별 쿼타와 비용 추적이 없어 특정 사용자가 자원을 독점합니다.
- 누가 언제 어떤 자원으로 무엇을 실행했는지 추적하기 어렵습니다.

`gpum`은 [Safety and limits](../safety.md), [Operating flow](../operating-flow.md), [All CLI command examples](../commands.md)를 통해 이 문제들을 제출 전, 실행 중, 종료 후 단계로 나눠 다룹니다.

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

자세한 설치 흐름은 [Installation](../installation.md)을 확인하세요.

## Quickstart

30초 안에 상태 확인, 작업 제출, 모니터링까지 이어지는 기본 흐름입니다.

```bash
gpum scan --refresh
gpum submit --image registry.example.com/ml/train:1.1.0 --gpu a100:2 --cpu 16 --memory 96g --command "python train.py"
gpum watch job-20260510-001
```

## 운영 흐름

1. `gpum scan --refresh`로 노드, GPU, MIG, NVLink, 온도, 전력, 메모리 상태를 갱신합니다.
2. `gpum dry-run ...`으로 쿼타, RBAC, 비용, 토폴로지, 컨테이너 옵션을 사전 검증합니다.
3. `gpum submit ...`으로 작업을 제출하면 스케줄러가 best-fit, worst-fit, gang scheduling, preemption, backfilling 정책을 적용합니다.
4. `gpum watch <job-id>`와 `gpum logs <job-id> --follow`로 실행 상태와 로그를 확인합니다.
5. `gpum report cost ...`와 `gpum audit ...`으로 비용과 감사 기록을 남깁니다.

전체 연결 구조는 [Operating flow](../operating-flow.md)를 참고하세요.

## 실무 안전 기능

- GPU 온도, 전력, ECC, XID 오류를 감시하고 임계치 초과 시 작업을 정지하거나 격리합니다.
- CPU/RAM/cgroup 한도를 적용해 한 작업이 서버 전체를 잠식하지 못하게 합니다.
- 컨테이너 `/dev/shm`, GPU pass-through, read-only dataset mount, checkpoint 정책을 실행 전에 검증합니다.
- 좀비 프로세스와 잔류 GPU 메모리를 탐지하고 안전한 회수 절차를 수행합니다.
- PostgreSQL 기반 감사 로그와 Redis 기반 락으로 중복 할당과 레이스 컨디션을 줄입니다.

자세한 정책은 [Safety and limits](../safety.md)에서 확인하세요.

## 문서 링크

- [루트 README](../../README.md)
- [문서 인덱스](../README.md)
- [사용자 가이드](../USER_GUIDE.md)
- [Overview](../overview.md)
- [Installation](../installation.md)
- [Operating flow](../operating-flow.md)
- [Safety and limits](../safety.md)
- [All CLI command examples](../commands.md)
- [Operational recipes](../recipes.md)
- [Production operations](../operations.md)
- [Release notes](../../README.r.md)
