# gpum Status Description

## 1. What is already implemented

The project already has a working base for the infrastructure-facing side of the system.

- Java 21 + Gradle + fat JAR build
- CLI command name: `gpum`
- inventory detection for:
  - NVIDIA (`nvidia-smi`)
  - AMD (`amd-smi`, `rocm-smi`)
  - Intel (`xpu-smi`)
- common `GpuDevice` model across vendors
- capability resolution for:
  - MIG
  - NVLink
  - XGMI
  - Intel link capability detection
- SQLite persistence for:
  - nodes
  - gpus
  - node attributes
- node metadata management:
  - drain / undrain
  - maintenance mode
  - labels
- inventory display:
  - ASCII table output
  - JSON GPU stats output
- system maintenance helpers:
  - DB integrity check
  - DB vacuum
  - DB backup / restore
- parser and inventory tests

## 2. Commands that actually work now

### Node

- `gpum node scan`
- `gpum node list`
- `gpum node info <host>`
- `gpum node drain <host>`
- `gpum node undrain <host>`
- `gpum node top`
- `gpum node maintenance <host> --on|--off`
- `gpum node label <host> --set ...`

### GPU

- `gpum gpu list`
- `gpum gpu stats`
- `gpum gpu health`
- `gpum gpu topology`

### System

- `gpum system config`
- `gpum system db-check`
- `gpum system health`
- `gpum system backup`
- `gpum system restore`

## 3. Commands that exist but are still placeholders

These commands are registered in the CLI and their inputs are validated, but they do not have real backend logic yet.

- `gpum alloc ...`
- `gpum part ...`
- `gpum queue ...`
- `gpum quota ...`
- `gpum audit ...`
- `gpum report ...`
- `gpum gpu set ...`
- `gpum gpu reset ...`
- `gpum system update`

## 4. Exception prevention already added

- invalid numeric ranges are blocked
- missing required values are blocked
- mutually exclusive options are checked
- detector command failures do not crash the whole scan
- unsupported local environment is reported as warnings
- SQLite schema is auto-created before read/write
- CLI execution errors are converted into readable user-facing messages

## 5. What still needs real implementation

### Highest priority

1. allocation data model
2. allocation repository tables
3. scheduler candidate selection
4. `alloc request`
5. `alloc list`
6. `alloc release`
7. lease expiration / reclaim logic

### After alloc

1. queue backend
2. quota backend
3. audit event store
4. report generation

### Hardware control backend

1. GPU power limit apply
2. GPU clock fix apply
3. ECC mode switch
4. compute mode switch
5. GPU reset flow

### Advanced infra backend

1. MIG partition create / destroy
2. partition inventory
3. auto-optimize logic
4. remote SSH scan
5. multi-node discovery
6. job/process aware drain and eviction

## 6. Recommended next milestone

The next correct milestone is not adding more command names. It is making the allocation path real:

- DB schema for allocations
- request -> allocate -> release lifecycle
- scheduler matching by vendor / model / VRAM / capability / labels
- safe duplicate-allocation prevention
- lease expiration cleanup

Until that exists, `gpum` is an inventory/control foundation, not a complete GPU leasing platform.

## 7. Expanded Target Architecture

### Infrastructure & Detection

- Multi-vendor detector
  - NVIDIA / AMD / Intel command integration
  - topology map generation
- Remote node handler
  - SSH mode
  - optional lightweight agent mode
  - hybrid agentless + agent architecture
- Health checker
  - real-time thermal / power / ECC watch
  - automatic `Degraded` state transition

### Scheduling & Allocation

- intelligent scheduler
  - affinity-aware placement
  - NVLink / P2P locality
  - fragmentation-aware bin-packing
- lease & reaper service
  - TTL management
  - expiry reclaim
  - pre-expiry warning
- transaction manager
  - concurrency control
  - duplicate allocation blocking
- MIG / partition manager
  - partition create / destroy / inventory

### Governance & Policy

- multi-tenant quota
  - per-user / per-tenant caps
  - hard quota / soft quota
- priority queue
  - weighted ordering
  - aging support
- preemption engine
  - low-priority reclaim
  - checkpoint-aware eviction path

### Data & Analytics

- SQLite / PostgreSQL persistence split
- immutable audit logger
- usage, cost, anomaly reporting

### Advanced Operations

- process cleaner
- maintenance workflow automation
- richer CLI output modes
- REST API bridge

## 8. More Advanced Features To Add Later

- scheduler decision explain mode
- ranked dry-run output
- power-aware / cost-aware placement
- predictive maintenance signals
- Prometheus metrics
- OpenTelemetry tracing
- webhook notifications
- RBAC / SSO bridge
- Slurm integration
- Kubernetes bridge
- billing policy engine
- capacity forecasting

## 9. Updated Roadmap

1. Phase 1
   - local inventory
   - basic list / alloc / release
2. Phase 2
   - remote scan
   - lease reaper
   - multi-node state
3. Phase 3
   - quota
   - queue
   - audit
   - report
4. Phase 4
   - MIG
   - preemption
   - process cleanup
   - health automation
5. Phase 5
   - REST API
   - PostgreSQL mode
   - observability
   - predictive operations
