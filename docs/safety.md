# Safety Model

`gpum` is designed as a guarded operations tool. Commands that can mutate hardware, kill processes, execute external tools, or change cluster state are preview-first.

## Physical Damage Prevention

GPU hardware operations are conservative by default.

- Hardware writes require `--apply`
- Hardware writes require `GPUM_ENABLE_HARDWARE_WRITE=1`
- High-risk apply paths require RBAC approval unless the actor already has the required role
- Active allocations block GPU mutation unless explicitly allowed where supported
- Hard reset is intentionally blocked
- Unsafe clock mutation is intentionally blocked
- ECC changes require `--allow-reboot-required`
- Linked GPU reset requires `--allow-linked-reset`
- Power limit changes validate policy and device bounds before execution

## Quota and Limit Enforcement

Safety policy:

```sh
gpum system safety policy --max-gpus-per-request 16 --max-lease-hours 168 --thermal-warn-c 75 --thermal-critical-c 85 --min-free-vram-ratio 0.05 --max-power-limit-w 900 --min-disk-free-gb 20 --heartbeat-stale-sec 120 --max-job-shm-gb 512
```

The active safety policy is used by:

- local allocation request checks
- remote server allocation request checks
- batch job shared-memory validation
- safety preflight checks

Compute policy inputs also have upper bounds for CPU cores, memory, PIDs, GPU model quota, and RDMA bandwidth.

## Exception Handling

CLI parse and execution failures are normalized into:

```text
ERROR: <reason>
HINT: run `<command> --help` for command usage.
```

This keeps operator feedback predictable in scripts and terminals.

## Preflight Checks

```sh
gpum system safety check
gpum system safety check --quarantine
gpum system safety check --fail-on-warn
```

Checks include:

- missing GPU inventory
- thermal warning and critical thresholds
- power saturation
- VRAM saturation
- power limit above policy
- expired active allocations
- low metadata disk headroom
- stale server heartbeat records
- hardware-write opt-in accidentally left enabled

## Incident Records

```sh
gpum system safety incident --node gpu-node-a --gpu-id 0 --severity warning --action quarantine --message "thermal anomaly"
gpum system safety incident --node gpu-node-a --severity critical --action drain --message "fan failure"
```

Incident actions can:

- record an audit-friendly ops record
- quarantine one GPU
- drain a node from scheduling

