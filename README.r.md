# gpum v1.0.0

First public release of `gpum`, a multi-vendor GPU inventory and allocation CLI for AI training and inference servers.

## Release Summary

`gpum` provides one operational surface for:

- NVIDIA / AMD / Intel GPU inventory
- node and GPU visibility
- SQLite-backed metadata persistence
- allocation lifecycle commands
- audit trail and operational logs
- governance primitives such as queue, quota, and logical partition records
- Kubernetes / MLflow / BentoML integration entrypoints

This release is focused on practical CLI workflows that can run on both development machines and real GPU servers.

---

## Main Features

### 1. Multi-Vendor GPU Detection

Supported vendors:

- NVIDIA
- AMD
- Intel

Representative GPU family coverage:

- NVIDIA: `H100`, `H200`, `B200`, `A100`, `RTX 4090`, `RTX 6000 Ada`, `L40S`
- AMD: `MI210`, `MI250X`, `MI300X`, `Radeon PRO W7900`
- Intel: `Data Center GPU Max 1100`, `Max 1550`, `Arc Pro A60`, `Flex 170`

Windows-specific Intel fallback support is included for:

- Intel Arc
- Intel Arc Pro
- Intel Flex
- Intel Max

When `xpu-smi` is missing on Windows, `gpum` can still detect Intel display adapters through PowerShell-based inventory fallback.

### 2. Node Management

Implemented commands:

- `gpum node scan`
- `gpum node scan --all`
- `gpum node scan --ip <addr> --ssh-user <user>`
- `gpum node list`
- `gpum node info`
- `gpum node top`
- `gpum node drain`
- `gpum node undrain`
- `gpum node maintenance`
- `gpum node label`
- `gpum node remote add`
- `gpum node remote list`
- `gpum node remote remove`

Notes:

- `node drain`, `node undrain`, `node maintenance`, and `node label` default to the local host if `HOST` is omitted.
- Remote scan uses SSH.

### 3. GPU Operations

Implemented commands:

- `gpum gpu list`
- `gpum gpu stats`
- `gpum gpu health`
- `gpum gpu topology`
- `gpum gpu set`
- `gpum gpu reset`

Current behavior:

- `gpu list`, `stats`, `health`, and `topology` work against the inventory database
- `gpu stats` can export to:
  - CSV
  - Influx line protocol
- `gpu set` and `gpu reset` validate input and record requests safely
- destructive vendor-level control is intentionally conservative in `v1.0.0`

### 4. Allocation Lifecycle

Implemented commands:

- `gpum alloc request`
- `gpum alloc request --dry-run`
- `gpum alloc list`
- `gpum alloc info`
- `gpum alloc extend`
- `gpum alloc release`
- `gpum alloc move`
- `gpum alloc reap`

Behavior included in this release:

- dry-run placement
- SQLite-backed allocation persistence
- GPU claim tracking
- basic move workflow
- expiry / reap support
- queue fallback when quota or capacity blocks immediate placement

### 5. Governance, Audit, and Logs

Implemented commands:

- `gpum queue list`
- `gpum queue promote`
- `gpum queue demote`
- `gpum quota set`
- `gpum quota status`
- `gpum quota alert`
- `gpum part create`
- `gpum part list`
- `gpum part destroy`
- `gpum part auto-optimize`
- `gpum report usage`
- `gpum report billing`
- `gpum audit list`
- `gpum audit trace`
- `gpum log write`
- `gpum log list`
- `gpum log tail`

### 6. Integrations

Implemented integration entrypoints:

- Kubernetes
- MLflow
- BentoML
- custom external tools via YAML config

Commands:

- `gpum integration k8s ...`
- `gpum integration mlflow ...`
- `gpum integration bentoml ...`
- `gpum integration tool --name <tool>`

### 7. System Operations

Implemented commands:

- `gpum system config`
- `gpum system db-check`
- `gpum system health`
- `gpum system backup`
- `gpum system restore`
- `gpum system update`

Included behavior:

- config inspection
- config editing entrypoint
- SQLite integrity check
- orphan cleanup
- backup / restore
- launcher refresh

---

## Installation

### Windows

Installer:

- `install.cmd`

Behavior:

- downloads `gpu-mgr.jar`
- compares installed version and release version
- skips download if installed version is the same or newer
- asks before upgrading if installed version is older
- writes `gpum.cmd`
- updates user `PATH` without using `setx`

Default install path:

```text
%LocalAppData%\gpum
```

### Linux

Installer:

- `install-gpum.sh`

Behavior:

- downloads `gpu-mgr.jar`
- compares installed version and release version
- skips download if installed version is the same or newer
- asks before upgrading if installed version is older
- writes `gpum`
- adds install path to shell profile if needed

Default install path:

```text
$HOME/.local/bin
```

### Portable Use

Also included:

- `gpu-mgr.jar`
- `gpum.cmd`
- `gpum.ps1`
- `gpum`

These launchers expect `gpu-mgr.jar` to be in the same directory.

---

## Quick Start

### Local Inventory

```bash
gpum node scan
gpum node list
gpum node info
gpum gpu list
```

### Remote Inventory

```bash
gpum node remote add --ip 10.0.0.20 --ssh-user gpuadmin --alias trainer-a
gpum node remote list
gpum node scan --ip 10.0.0.20 --ssh-user gpuadmin
```

### Allocation

```bash
gpum alloc request --gpus 1 --vram 60000 --hours 4 --dry-run
gpum alloc request --gpus 1 --vram 60000 --hours 4
gpum alloc list
gpum alloc info --id <allocation-id>
```

### Logs and Audit

```bash
gpum log write --level info --component system --category startup --message "boot ok"
gpum log list --sort desc --limit 20
gpum audit list --tail 20
```

---

## Persistence

Default database:

```text
data/gpu-mgr.db
```

Stored data includes:

- nodes
- GPUs
- node labels and attributes
- remote node registrations
- allocations
- queue entries
- partition records
- quota policies
- audit events
- operational logs

---

## Quality and Testing

This release includes:

- detector fixture parsing tests
- mixed-vendor fleet tests
- CLI command matrix tests
- SQLite-backed service and repository tests

This means most workflows can be validated without owning expensive GPU hardware.

---

## Known Limits in v1.0.0

Still conservative or partial:

- real vendor-level GPU power / clock mutation
- real GPU reset execution
- process cleanup on release
- full MIG lifecycle control
- advanced queue scheduling and preemption
- deep RDMA / NUMA / NIC analysis on every platform
- rich Intel metrics on Windows without vendor tooling

---

## Recommended Release Assets

Attach these files to the GitHub Release:

- `gpu-mgr.jar`
- `gpum.cmd`
- `gpum.ps1`
- `gpum`
- `install.cmd`
- `install-gpum.sh`
- `gpum-version.txt`
- `gpum.example.yaml`
- `gpum-dist.zip`

---

## Suggested Release Title

```text
gpum v1.0.0
```
