# GPUManager

AI 학습용 GPU 서버 자원을 할당하고 관리하는 Java 21 기반 CLI JAR 프로젝트.

이 프로젝트의 목표는 단순한 `nvidia-smi` 래퍼가 아니라, 여러 서버에 걸쳐 존재하는 GPU/CPU/RAM/스토리지 자원을 탐지하고, 정책에 따라 안전하게 예약/할당/회수하는 운영 도구를 만드는 것이다. 기본 배포 형태는 `gpu-mgr.jar`이며, 우선순위는 Linux GPU 서버 운영 환경이다.

## 1. 프로젝트 목표

- NVIDIA와 AMD GPU를 모두 지원하는 통합 자원 관리 도구
- 단일 서버가 아니라 여러 GPU 서버를 하나의 자원 풀처럼 관리
- 연구원/팀/프로젝트 단위의 할당 정책, 쿼터, 예약, 대기열 지원
- 학습 작업 실행 전후의 자원 점유 상태를 추적하고 감사 로그를 남김
- CLI 우선 설계, 필요 시 REST API/웹 UI는 이후 확장

## 2. 반드시 지원해야 하는 하드웨어 범위

### NVIDIA

- 데이터센터 계열: `V100`, `A100`, `H100`, `H200`, `B200`, `L4`, `L40`, `L40S`
- 워크스테이션/RTX 계열: `RTX Axxxx`, `RTX 20/30/40/50 시리즈`
- 기능 차이 고려:
  - MIG 지원 GPU와 비지원 GPU 구분
  - CUDA 가능 여부
  - NVLink/NVSwitch 토폴로지
  - ECC 지원 여부
  - 전력 제한/클럭/온도 상태 조회

### AMD

- Instinct 계열: `MI100`, `MI200`, `MI210`, `MI250`, `MI300` 시리즈
- Radeon Pro / ROCm 호환 가능 계열
- 기능 차이 고려:
  - ROCm 가능 여부
  - XGMI/Infinity Fabric 연결 정보
  - VRAM/ECC/전력/온도 상태 조회
  - 일부 모델에서 관리 명령 범위가 제한될 수 있음

### Intel (추후 지원 대상)

- 데이터센터/AI 계열: `Intel Data Center GPU Max` 시리즈
- 워크스테이션/일반 계열: `Intel Arc Pro`, `Intel Arc`
- 추후 고려 기능:
  - oneAPI / Level Zero 지원 여부
  - XPU 계열 디바이스 식별
  - VRAM / 온도 / 전력 / utilization 조회
  - 드라이버 및 런타임 호환성 확인

### 설계 원칙

- 특정 모델 이름으로 분기하지 말고 `vendor + family + capabilities` 기반으로 추상화
- 같은 벤더 안에서도 모델마다 기능 차이가 크므로 `기능 탐지(capability discovery)`를 먼저 수행
- 지원 여부는 "이름 매칭"이 아니라 "실행 가능한 관리 기능 집합"으로 판정
- 향후 Intel을 추가할 수 있도록 벤더 종속 로직은 detector/capability 계층에 격리

## 3. 우선 지원 OS / 런타임

- 1차 운영 대상: `Ubuntu` / `RHEL 계열` Linux 서버
- 제어 노드 CLI는 Windows/macOS에서도 동작 가능하게 설계 가능
- JDK 21
- Gradle build
- 서버 측 필수 툴:
  - NVIDIA: `nvidia-smi`
  - AMD: `rocm-smi` 또는 `amd-smi`
- 선택 통합:
  - Docker / Podman
  - Slurm
  - Kubernetes
  - SSH 기반 원격 실행

## 4. 이 프로젝트에서 만들어야 하는 핵심 기능

### 4.1 서버 및 GPU 인벤토리 수집

- 등록된 서버 목록 관리
- 서버별 하드웨어 스캔
  - hostname
  - CPU 코어
  - 시스템 메모리
  - 로컬 스토리지
  - NIC / IB 정보
  - GPU 개수
  - GPU별 UUID / PCI Bus ID / 모델 / VRAM / 드라이버 / 상태
- 정기적인 하트비트와 상태 갱신
- 서버 장애/오프라인 상태 감지

### 4.2 GPU 추상화 계층

GPU를 아래 공통 모델로 표현해야 한다.

- `vendor`: NVIDIA / AMD
- `model`
- `deviceId`
- `uuid`
- `vramTotalMb`
- `vramFreeMb`
- `utilizationGpu`
- `utilizationMemory`
- `temperatureC`
- `powerUsageW`
- `powerLimitW`
- `eccEnabled`
- `interconnectType`
- `healthState`
- `supportsMig`
- `supportsPartitioning`
- `supportsCompute`
- `supportsContainerRuntime`

벤더별 구현은 다르더라도 상위 계층에서는 동일한 인터페이스로 다뤄야 한다.

### 4.3 자원 할당 단위 정의

최소한 아래 단위를 지원해야 한다.

- 서버 단위 할당
- GPU 개수 단위 할당
- 특정 GPU 지정 할당
- VRAM 최소치 기반 할당
- MIG/파티션 단위 할당
- CPU/RAM/스토리지 동반 예약
- 기간 기반 lease 할당

예시:

- `H100 2장 이상`
- `B200 8장 + NVLink 선호`
- `RTX 계열 1장 + VRAM 20GB 이상`
- `AMD MI300 계열 4장`
- `CUDA 필수`
- `ROCm 필수`

### 4.4 스케줄링 / 정책 엔진

반드시 정의해야 할 정책:

- 즉시 할당
- 대기열 등록
- 우선순위 기반 스케줄링
- 팀/사용자 쿼터
- 공정 점유(fair-share)
- 최대 점유 시간
- 예약 종료 후 자동 회수
- idle timeout 기반 회수
- 선점 가능 작업과 비선점 작업 구분

추천 스케줄링 전략:

- 1순위: 기능 충족 여부
- 2순위: fragmentation 최소화
- 3순위: 같은 노드 집적 배치
- 4순위: 인터커넥트 품질 우선
- 5순위: 팀별 공정성

### 4.5 예약 / 점유 / 회수 라이프사이클

- `REQUESTED`
- `QUEUED`
- `ALLOCATED`
- `RUNNING`
- `RELEASING`
- `RELEASED`
- `EXPIRED`
- `FAILED`
- `PREEMPTED`

필수 동작:

- 예약 생성
- 예약 승인/거절
- 점유 시작
- 점유 연장
- 점유 종료
- 강제 회수
- 만료 자동 정리
- 장애 발생 시 orphan allocation 복구

### 4.6 작업 실행 연동

최소 범위에서는 실제 학습 프레임워크를 직접 실행하지 않아도 되지만, 아래 훅은 필요하다.

- 할당 전 검증 훅
- 할당 후 실행 훅
- 종료 후 정리 훅
- 실패 시 롤백 훅

예시 연동 대상:

- Python training script
- Docker container
- SSH remote command
- Slurm submit wrapper

### 4.7 상태 조회 및 운영자 기능

- 현재 사용 가능 GPU 조회
- 사용자별/팀별 점유 현황 조회
- 모델별 재고 조회
- 서버별 장애 상태 조회
- 장기 점유 작업 조회
- idle GPU 조회
- 할당 이력 조회
- 강제 해제
- 노드 drain / undrain
- 유지보수 모드 전환

### 4.8 감사 로그 / 이력 관리

반드시 남겨야 하는 로그:

- 누가
- 언제
- 어떤 자원을
- 어떤 조건으로 요청했는지
- 실제 어떤 서버/디바이스에 배정되었는지
- 언제 반환되었는지
- 누가 강제 종료했는지

로그 종류:

- audit log
- scheduler decision log
- health check log
- allocation event log

## 5. 비기능 요구사항

### 안정성

- 중복 할당 금지
- 프로세스 재시작 후 상태 복구 가능
- 부분 장애 상황에서도 메타데이터 일관성 유지
- 실패한 외부 명령 실행에 대한 timeout / retry / backoff

### 확장성

- 수십 대 서버 / 수백 장 GPU까지 감당 가능한 구조
- 벤더별 구현체 추가가 쉬운 구조
- 향후 REST API / Web UI / Kubernetes operator 확장 가능

### 보안

- 관리자 명령과 일반 사용자 명령 분리
- 최소 권한 원칙
- 원격 실행 시 SSH 키 관리
- 민감 정보 마스킹
- 감사 가능한 관리자 액션

### 운영성

- 텍스트 표 출력
- JSON/YAML 출력 지원
- verbose / debug 로그 레벨 분리
- 상태 점검 명령 제공

## 6. 추천 아키텍처

현재 의존성을 기준으로 다음 구조가 적절하다.

```text
src/main/java/com/drewdrew1
  App.java
  cli/
    GpuMgrCommand.java
    commands/
  core/
    model/
    service/
    scheduler/
    policy/
  infra/
    detector/
      nvidia/
      amd/
    executor/
    persistence/
    config/
  api/
```

### 레이어별 책임

- `cli`
  - picocli 기반 명령 파싱
  - 표/JSON 출력
- `core.model`
  - Node, GpuDevice, Allocation, Lease, UserQuota 같은 도메인 객체
- `core.service`
  - 인벤토리 수집, 할당, 회수, 조회 서비스
- `core.scheduler`
  - 후보 노드 계산, 정책 적용, 우선순위 평가
- `core.policy`
  - quota, reservation, fairness, preemption 규칙
- `infra.detector`
  - `nvidia-smi`, `rocm-smi`, `amd-smi` 호출 및 파싱
- `infra.executor`
  - 로컬/SSH/컨테이너 명령 실행
- `infra.persistence`
  - SQLite 저장소
- `infra.config`
  - YAML 설정 로드

## 7. 꼭 분리해서 설계해야 하는 인터페이스

### `GpuDetector`

- 서버에서 GPU 목록과 상태를 읽어옴
- NVIDIA/AMD 구현 분리

### `HealthCollector`

- 온도, utilization, 메모리, 전력, ECC, 장애 상태 수집

### `Scheduler`

- 요청 조건을 만족하는 후보 자원을 계산

### `AllocationRepository`

- 할당 상태 영속화

### `CommandExecutor`

- 로컬 명령 / SSH 명령 실행 추상화

### `CapabilityResolver`

- 장치가 CUDA/ROCm/oneAPI/MIG/partitioning/NVLink/XGMI를 지원하는지 판단

## 8. 저장해야 하는 도메인 엔티티

- `Node`
- `GpuDevice`
- `GpuPartition`
- `InventorySnapshot`
- `AllocationRequest`
- `Allocation`
- `Lease`
- `Tenant`
- `User`
- `QuotaPolicy`
- `ReservationPolicy`
- `HealthEvent`
- `AuditEvent`

## 9. SQLite 스키마 초안

최소 테이블:

- `nodes`
- `gpus`
- `gpu_partitions`
- `allocation_requests`
- `allocations`
- `leases`
- `tenants`
- `users`
- `quota_policies`
- `audit_events`
- `health_events`

최소 제약:

- 동일 GPU/파티션에 대해 활성 할당 중복 금지
- 만료되지 않은 lease 중복 금지
- node/gpu UUID unique

## 10. CLI에서 제공해야 하는 명령

예시:

```bash
java -jar gpu-mgr.jar node scan
java -jar gpu-mgr.jar node list
java -jar gpu-mgr.jar gpu list
java -jar gpu-mgr.jar gpu stats
java -jar gpu-mgr.jar alloc request --vendor nvidia --model H100 --gpus 2
java -jar gpu-mgr.jar alloc request --vendor amd --family MI300 --gpus 4
java -jar gpu-mgr.jar alloc request --vendor nvidia --min-vram 20480 --gpus 1
java -jar gpu-mgr.jar alloc approve <requestId>
java -jar gpu-mgr.jar alloc release <allocationId>
java -jar gpu-mgr.jar alloc extend <allocationId> --hours 12
java -jar gpu-mgr.jar queue list
java -jar gpu-mgr.jar quota list
java -jar gpu-mgr.jar audit list
```

최소 명령 그룹:

- `node`
- `gpu`
- `alloc`
- `queue`
- `quota`
- `tenant`
- `audit`
- `config`
- `health`

## 11. 설정 파일에서 다뤄야 하는 항목

`config.yaml` 예시:

```yaml
server:
  pollIntervalSec: 30
  leaseReconcileIntervalSec: 15

inventory:
  enableNvidia: true
  enableAmd: true
  commandTimeoutSec: 10

remote:
  mode: ssh
  sshUser: gpuadmin
  sshPort: 22

scheduler:
  strategy: balanced
  allowMixedVendors: false
  preferSameNode: true
  preferHighBandwidthInterconnect: true
  idleReleaseMinutes: 30

policies:
  defaultMaxLeaseHours: 24
  allowPreemption: false

storage:
  sqlitePath: ./data/gpu-mgr.db
```

## 12. 벤더별 명령 처리 전략

### NVIDIA

최소 수집 대상:

- `nvidia-smi --query-gpu=... --format=csv,noheader,nounits`
- GPU UUID
- 모델명
- 메모리 총량/사용량
- GPU 사용률
- 온도
- 전력 사용량
- 드라이버 버전
- MIG 모드

### AMD

최소 수집 대상:

- `rocm-smi` 또는 `amd-smi`
- GPU UUID 또는 식별 가능한 디바이스 키
- 모델명
- VRAM 총량/사용량
- GPU 사용률
- 온도
- 전력
- ROCm 가능 여부

### 구현상 주의

- 쉘 출력 파싱은 취약하므로 가능한 한 구조화된 출력 옵션을 우선 사용
- 벤더 명령 실패를 전체 시스템 실패로 확대하지 말고, 노드별 degraded 상태로 처리
- 드라이버/툴 버전별 출력 포맷 차이를 감안해 파서 테스트를 분리

## 13. MVP에서 꼭 끝내야 하는 범위

### Phase 1

- 단일 노드 스캔
- NVIDIA/AMD 공통 GPU 모델 추상화
- SQLite 저장
- CLI 조회
- 기본 할당/회수
- lease 만료 처리

### Phase 2

- 멀티 노드 지원
- 원격 SSH 수집
- 대기열
- 쿼터 정책
- 감사 로그

### Phase 3

- MIG/파티션 지원
- 선점 정책
- Slurm/Kubernetes 연동
- REST API

## 14. 구현 우선순위

1. 공통 도메인 모델 정의
2. YAML 설정 로더 작성
3. SQLite repository 작성
4. NVIDIA detector 작성
5. AMD detector 작성
6. 인벤토리 스캔 명령 작성
7. 할당 서비스와 스케줄러 작성
8. lease 만료/회수 처리 작성
9. 운영 조회 명령 작성
10. 감사 로그와 테스트 보강

## 15. 테스트 전략

반드시 있어야 하는 테스트:

- NVIDIA CLI 출력 파서 테스트
- AMD CLI 출력 파서 테스트
- 스케줄러 후보 선택 테스트
- 중복 할당 방지 테스트
- lease 만료 테스트
- quota 위반 테스트
- 노드 장애 복구 테스트

테스트 종류:

- 단위 테스트
- 저장소 테스트(SQLite)
- 통합 테스트(샘플 명령 출력 기반)
- 회귀 테스트(실제 장비가 없어도 샘플 출력으로 검증)

## 16. 완료 기준

아래를 만족하면 1차 버전으로 볼 수 있다.

- NVIDIA와 AMD 장비를 모두 스캔할 수 있음
- GPU 모델/VRAM/상태를 공통 포맷으로 조회할 수 있음
- 요청 조건에 맞는 GPU를 배정할 수 있음
- 중복 할당이 발생하지 않음
- 점유 만료와 회수가 동작함
- SQLite에 이력과 상태가 남음
- CLI만으로 운영자가 실사용 가능한 수준의 조회/제어가 가능함

## 17. 현재 프로젝트 기준 바로 만들 파일들

- `src/main/java/com/drewdrew1/App.java`
- `src/main/java/com/drewdrew1/cli/GpuMgrCommand.java`
- `src/main/java/com/drewdrew1/cli/commands/*`
- `src/main/java/com/drewdrew1/core/model/*`
- `src/main/java/com/drewdrew1/core/service/*`
- `src/main/java/com/drewdrew1/core/scheduler/*`
- `src/main/java/com/drewdrew1/core/policy/*`
- `src/main/java/com/drewdrew1/infra/detector/nvidia/*`
- `src/main/java/com/drewdrew1/infra/detector/amd/*`
- `src/main/java/com/drewdrew1/infra/executor/*`
- `src/main/java/com/drewdrew1/infra/persistence/*`
- `src/main/java/com/drewdrew1/infra/config/*`
- `src/test/java/com/drewdrew1/...`

## 18. 빌드/실행 목표

빌드 결과물:

```bash
./gradlew shadowJar
```

산출물:

- `build/libs/gpu-mgr.jar`

최종적으로는 아래 형태로 실행되는 것이 목표다.

```bash
java -jar build/libs/gpu-mgr.jar gpu list
java -jar build/libs/gpu-mgr.jar alloc request --vendor nvidia --model H100 --gpus 2
```

## 19. 권장 개발 원칙

- 벤더별 구현을 서비스 로직과 분리
- 문자열 파싱 로직을 테스트 가능하게 작은 단위로 분리
- 상태 조회와 자원 변경 명령을 구분
- "GPU 이름"보다 "기능(capability)"을 기준으로 스케줄링
- 나중에 REST API를 얹을 수 있게 core 계층을 CLI와 분리

## 20. 다음 구현 순서 제안

가장 현실적인 시작 순서는 아래다.

1. `config.yaml` 로더
2. `node scan`, `gpu list` CLI
3. NVIDIA detector
4. AMD detector
5. SQLite inventory 저장
6. `alloc request`, `alloc release`
7. lease 만료 처리
8. audit/health 로그

이 README는 소개 문서라기보다 구현 명세 초안에 가깝다. 먼저 MVP 범위를 Phase 1으로 고정한 뒤, 그 다음에 detector와 allocation 흐름부터 코드로 만드는 것이 맞다.

## 21. 실제 작업 순서

아래 순서대로 작업하면 된다. 앞 단계를 끝내기 전에는 뒤 단계로 넘어가지 않는 편이 좋다.

### Step 1. 프로젝트 뼈대 정리

- `cli`, `core`, `infra` 패키지 생성
- `App.java`에서 picocli 엔트리포인트 연결
- 공통 예외 타입, 공통 응답 포맷, 출력 유틸 정의

완료 기준:

- `java -jar ... --help` 수준의 기본 CLI가 뜰 것

### Step 2. 설정 로딩

- `config.yaml` 스키마 정의
- Jackson YAML 로더 작성
- 기본값 처리와 유효성 검사 추가

완료 기준:

- 설정 파일 없이도 기본값으로 실행 가능
- 설정 파일이 있으면 정상 로딩 가능

### Step 3. 도메인 모델 정의

- `Node`
- `GpuDevice`
- `GpuPartition`
- `AllocationRequest`
- `Allocation`
- `Lease`
- `Tenant`
- `QuotaPolicy`

완료 기준:

- 벤더와 무관하게 GPU를 표현할 수 있어야 함

### Step 4. 저장소 계층 작성

- SQLite 연결 관리
- 테이블 생성 로직
- `nodes`, `gpus`, `allocations`, `leases`, `audit_events` 저장소 작성

완료 기준:

- 스캔 결과와 할당 상태를 DB에 저장/조회 가능

### Step 5. 명령 실행 추상화

- 로컬 명령 실행기 작성
- timeout / stderr / exit code 처리
- 이후 SSH 실행기를 붙일 수 있도록 인터페이스 분리

완료 기준:

- 외부 명령 실행 결과를 안정적으로 수집 가능

### Step 6. NVIDIA detector 구현

- `nvidia-smi` 기반 GPU 탐지
- 공통 `GpuDevice` 변환
- 샘플 출력 기반 파서 테스트 작성

완료 기준:

- H100, B200, RTX 계열이 공통 포맷으로 조회 가능

### Step 7. AMD detector 구현

- `rocm-smi` 또는 `amd-smi` 기반 GPU 탐지
- 공통 `GpuDevice` 변환
- 샘플 출력 기반 파서 테스트 작성

완료 기준:

- MI 시리즈와 ROCm 가능 장비가 공통 포맷으로 조회 가능

### Step 8. 인벤토리 스캔 흐름 완성

- `node scan`
- `node list`
- `gpu list`
- `gpu stats`

완료 기준:

- 등록된 노드의 최신 GPU 상태를 조회 가능

### Step 9. 기본 할당 엔진 구현

- 요청 조건 파싱
- 후보 GPU 탐색
- 중복 할당 방지
- `ALLOCATED` 상태 저장

완료 기준:

- `alloc request`와 `alloc release`가 동작

### Step 10. lease / 만료 / 회수

- lease 시작/종료 시각 관리
- 만료 스캐너
- 자동 회수
- orphan allocation 정리

완료 기준:

- 만료된 점유가 자동으로 정리됨

### Step 11. 정책 계층 추가

- quota
- fair-share
- 우선순위
- idle timeout

완료 기준:

- 사용자/팀 정책을 적용한 할당이 가능

### Step 12. 감사 및 운영 기능

- `audit list`
- 강제 해제
- drain / undrain
- health event 기록

완료 기준:

- 운영자가 CLI만으로 상태 확인과 강제 제어 가능

### Step 13. 멀티 노드 / 원격 수집

- SSH executor
- 원격 노드 스캔
- 멀티 노드 스케줄링

완료 기준:

- 여러 GPU 서버를 하나의 풀처럼 관리 가능

### Step 14. 파티셔닝/MIG 지원

- MIG 인식
- 파티션 단위 할당
- fragmentation 최소화 로직

완료 기준:

- H100/A100 계열 파티션 자원도 관리 가능

### Step 15. 추후 Intel GPU 지원

Intel은 지금 바로 1차 구현 대상은 아니지만, 아래 순서로 붙일 수 있게 설계해야 한다.

1. `GpuVendor`에 `INTEL` 추가
2. `IntelDetector` 인터페이스/구현 추가
3. `CapabilityResolver`에 `oneAPI`, `Level Zero` 항목 추가
4. Intel CLI 출력 샘플 기반 파서 테스트 추가
5. 스케줄러에 `vendor=intel` 및 capability 필터 연결
6. 문서와 설정 파일에 Intel 옵션 추가

완료 기준:

- 기존 `core` 계층 변경을 최소화하면서 Intel detector만 추가해도 동작해야 함

### 추천 마일스톤

- Milestone 1: `Step 1` ~ `Step 5`
- Milestone 2: `Step 6` ~ `Step 8`
- Milestone 3: `Step 9` ~ `Step 10`
- Milestone 4: `Step 11` ~ `Step 13`
- Milestone 5: `Step 14` ~ `Step 15`

## 22. Current Implementation Status

As of 2026-05-07, the project has a working CLI surface, inventory detection engine, SQLite persistence, and basic operational metadata management. The command name is now `gpum`.

### Implemented and working

- CLI entrypoint:
  - `gpum`
- Hardware detection:
  - NVIDIA via `nvidia-smi`
  - AMD via `amd-smi` with `rocm-smi` fallback
  - Intel via `xpu-smi`
- Common GPU inventory model:
  - vendor / model / uuid / PCI / VRAM / util / temp / power / ECC / interconnect
- Capability resolution:
  - NVIDIA MIG detection
  - NVLink detection
  - AMD XGMI detection
  - Intel Xe Link style capability detection
- Persistence:
  - SQLite schema for nodes
  - SQLite schema for GPUs
  - SQLite schema for node attributes / labels / maintenance / drain state
- Node commands with working backend:
  - `gpum node scan`
  - `gpum node list`
  - `gpum node info`
  - `gpum node drain`
  - `gpum node undrain`
  - `gpum node top`
  - `gpum node maintenance`
  - `gpum node label`
- GPU commands with working backend:
  - `gpum gpu list`
  - `gpum gpu stats`
  - `gpum gpu health`
  - `gpum gpu topology`
- System commands with working backend:
  - `gpum system config`
  - `gpum system db-check`
  - `gpum system health`
  - `gpum system backup`
  - `gpum system restore`
- Safety / exception prevention:
  - shared CLI validation
  - range checks
  - required argument checks
  - mutually exclusive option checks
  - safe error handler for command failures
  - detector failure isolation per vendor

### Implemented as CLI contract only

The commands below exist, their options are validated, and they fail safely, but the business backend is not implemented yet.

- `gpum alloc *`
- `gpum part *`
- `gpum queue *`
- `gpum quota *`
- `gpum audit *`
- `gpum report *`
- `gpum gpu set`
- `gpum gpu reset`
- `gpum system update`

### Not implemented yet

- real allocation scheduler
- allocation persistence and lifecycle state machine
- queue promotion / demotion backend
- quota engine
- audit event store
- usage / billing reports
- MIG partition creation / destroy / optimization backend
- live allocation migration
- process discovery / kill tree handling
- remote SSH node scan
- multi-node cluster scan
- NUMA inspection
- RAM bandwidth measurement
- NIC / RDMA / InfiniBand deep inspection
- PCIe generation detection
- historical GPU metrics storage
- InfluxDB export backend
- GPU power / clock / ECC / compute-mode write control backend

## 23. Next Implementation Order

Recommended next order from here:

1. allocation domain model + SQLite tables
2. `alloc request/list/info/release/extend`
3. queue and quota engine
4. audit event logging
5. GPU control backend (`gpu set`, `gpu reset`)
6. MIG / partition backend
7. remote SSH scanning and multi-node discovery
8. report and billing backend

## 24. Reality Check

Right now this project is not yet a complete GPU resource manager. It is a solid inventory and control-plane foundation with a broad CLI contract already in place. The highest-value next milestone is to make `alloc` real before expanding more operational surface area.

## 25. Advanced Architecture Expansion

Below is the expanded target architecture for the next stages of the project.

### 25.1 Infrastructure & Detection

- Multi-Vendor Detector
  - `nvidia-smi`, `rocm-smi`, `intel-gputop` / `xpu-smi` execution and parsing
  - topology map generation for PCIe / NVLink / P2P / switch domains
- Remote Node Agent
  - SSH-based remote execution
  - optional lightweight agent mode
  - hybrid agentless + agent mode
- Health Checker
  - temperature / fan / power / ECC monitoring
  - degraded state transition on thermal throttling or health faults

### 25.2 Scheduling & Allocation

- Intelligent Scheduler
  - affinity-aware placement
  - same-node / same-fabric / same-NVLink-domain placement
  - fragmentation-aware bin-packing
- Lease & Reaper Service
  - TTL-based allocation expiry
  - automatic reclaim
  - pre-expiry notification and extension workflow
- Transaction Manager
  - concurrent request control
  - duplicate allocation prevention
  - DB transaction / locking strategy
- MIG / Partition Manager
  - physical GPU slicing
  - partition profile inventory
  - profile lifecycle management

### 25.3 Governance & Policy

- Multi-tenant Quota
  - per-user / per-tenant GPU limits
  - VRAM totals
  - GPU-hours limits
  - hard / soft quota separation
- Priority Queue
  - weighted queueing
  - aging to prevent starvation
- Preemption Engine
  - low-priority reclaim
  - checkpoint / resume aware eviction

### 25.4 Data & Analytics

- Persistence Layer
  - SQLite for local mode
  - PostgreSQL for multi-node / production mode
- Audit Logger
  - immutable lifecycle event trail
- Usage Reporter
  - utilization reports
  - cost reports
  - anomaly detection for abnormal power / usage patterns

### 25.5 Advanced Operations

- Process Cleaner
  - process tree cleanup on release
  - zombie process cleanup
- Node Maintenance Workflow
  - `Drain -> Evict -> Check -> Undrain`
- CLI UX Enhancements
  - tab completion
  - aliases
  - JSON / YAML / table output modes
- REST API Bridge
  - CLI-backed HTTP service mode

## 26. Additional Advanced Features

Beyond the current roadmap, these are worth adding as expert-level features.

### 26.1 Placement Explainability

- scheduler decision trace for every allocation
- why a node was selected or rejected
- dry-run output with ranked candidates

### 26.2 Cost-Aware Scheduling

- power-aware placement
- premium vs low-cost pool selection
- vendor/model-based internal billing classes

### 26.3 Predictive Operations

- predictive maintenance from ECC / thermal / power drift
- early warning for likely hardware degradation
- capacity forecast for future queue pressure

### 26.4 Observability

- Prometheus metrics
- OpenTelemetry tracing
- webhook/event streaming for allocation lifecycle

### 26.5 Security & Access Control

- RBAC for operator vs tenant vs user
- API token / SSO bridge
- tenant-level policy isolation
- audited admin overrides

### 26.6 Data Pipeline / Training Integration

- Slurm bridge
- Kubernetes device plugin integration layer
- container launch templates
- training job metadata hooks

## 27. Expanded Roadmap

1. Phase 1 (MVP)
   - single node scan
   - `gpu list`
   - manual allocation / release
2. Phase 2 (Management)
   - DB persistence
   - multi-node SSH scan
   - lease expiry / reaper
3. Phase 3 (Enterprise)
   - quota
   - priority queue
   - audit / reporting
4. Phase 4 (Expert)
   - MIG partitioning
   - preemption
   - process cleanup
   - monitoring / dashboard
5. Phase 5 (Platform)
   - REST API bridge
   - PostgreSQL mode
   - observability stack
   - predictive operations
