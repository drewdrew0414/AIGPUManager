# End-to-End Recipes

## Local Training Flow

```bash
gpum node scan --force --discovery-depth 2
gpum system safety policy --max-gpus-per-request 8 --max-lease-hours 72 --thermal-warn-c 75 --thermal-critical-c 85 --min-free-vram-ratio 0.05 --max-power-limit-w 900 --min-disk-free-gb 20 --heartbeat-stale-sec 120 --max-job-shm-gb 256
gpum system safety check
gpum rbac role grant --actor alice --role operator --tenant research
gpum quota set --name alice --max-gpus 4 --max-vram 320000 --max-lease-hours 72
gpum alloc request --gpus 2 --vram 80000 --hours 8 --tenant research --label-selector role=trainer --dry-run
gpum alloc request --gpus 2 --vram 80000 --hours 8 --tenant research --label-selector role=trainer
gpum integration ai env --allocation-id alloc-example --format shell
gpum job batch --name train --allocation-id alloc-example --image nvcr.io/nvidia/pytorch:24.12-py3 --engine docker --gpus all --shm-size 128g --command "python train.py"
gpum observe telemetry --name train-telemetry --interval-sec 5 --retention-hours 24
gpum report cost-estimate --owner alice --gpu-model H100 --gpus 2 --hours 8 --rate-per-gpu-hour 3.5
gpum alloc release --id alloc-example --kill-process
gpum audit trace alloc-example
```

## Central Server Flow

```bash
gpum server run --port 7070
gpum server heartbeat --host 127.0.0.1 --port 7070 --node gpu-node-a --status ALIVE --labels zone=nvlink --allocatable-gpus 8
gpum server allocate --host 127.0.0.1 --port 7070 --tenant research --owner alice --gpus 2 --vram 80000 --hours 8 --dry-run
gpum server allocate --host 127.0.0.1 --port 7070 --tenant research --owner alice --gpus 2 --vram 80000 --hours 8
gpum server submit --host 127.0.0.1 --port 7070 --allocation-id alloc-example --name train --image nvcr.io/nvidia/pytorch:24.12-py3 --command "python train.py"
gpum server telemetry --host 127.0.0.1 --port 7070 --filter gpu
gpum server release --host 127.0.0.1 --port 7070 --id alloc-example
```

## Incident Response Flow

```bash
gpum system safety check --quarantine
gpum system safety incident --node gpu-node-a --gpu-id 0 --severity critical --action quarantine --message "thermal critical"
gpum node drain gpu-node-a --graceful --timeout 300 --reason "thermal critical" --evict
gpum runtime zombie list --gpu-id gpu-node-a:0
gpum runtime zombie clean --gpu-id gpu-node-a:0 --force --execute
gpum audit list --tail 100
gpum system backup --path backups/post-incident.db
```

