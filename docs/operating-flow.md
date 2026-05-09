# Operating Flow

This is how the main command groups connect in a practical run.

## 1. Scan Inventory

```sh
gpum node scan --force --discovery-depth 1
gpum node list --sort gpu
gpum gpu list
```

Inventory is the base layer. Scheduling, allocation, safety checks, reports, and server resource summaries all depend on this data.

## 2. Set Guardrails

```sh
gpum system safety policy --max-gpus-per-request 8 --max-lease-hours 72 --thermal-warn-c 75 --thermal-critical-c 85 --min-free-vram-ratio 0.05 --max-power-limit-w 900 --min-disk-free-gb 20 --heartbeat-stale-sec 120 --max-job-shm-gb 256
gpum system safety check
```

Safety policy is checked before oversized allocation and job shared-memory requests are accepted.

## 3. Configure Governance

```sh
gpum rbac role grant --actor alice --role operator --tenant research
gpum quota set --name alice --max-gpus 4 --max-vram 320000 --max-lease-hours 72
gpum quota status --user alice --remaining
```

RBAC controls privileged operations. Quota controls normal allocation demand.

## 4. Request GPU Resources

```sh
gpum alloc request --gpus 2 --vram 80000 --hours 8 --tenant research --label-selector role=trainer --dry-run
gpum alloc request --gpus 2 --vram 80000 --hours 8 --tenant research --label-selector role=trainer
gpum alloc info --id alloc-example
```

Dry-run shows the candidate. A real request persists a lease.

## 5. Connect the Allocation to Execution

```sh
gpum integration ai env --allocation-id alloc-example --format shell
gpum job batch --name train --allocation-id alloc-example --image nvcr.io/nvidia/pytorch:24.12-py3 --engine docker --gpus all --shm-size 128g --command "python train.py"
```

The allocation ID becomes the link between resource ownership, environment variables, execution plans, and later reports.

## 6. Observe and Report

```sh
gpum observe telemetry --name train-telemetry --interval-sec 5 --retention-hours 24
gpum observe log-stream --lines 50
gpum report cost-estimate --owner alice --gpu-model H100 --gpus 2 --hours 8 --rate-per-gpu-hour 3.5
```

Observability tracks state. Reports convert usage into operational and cost views.

## 7. Release and Preserve Evidence

```sh
gpum alloc release --id alloc-example --kill-process
gpum alloc reap
gpum audit trace alloc-example
gpum system backup --path backups/gpum-backup.db
```

Release returns capacity. Audit and backup preserve the operational record.

