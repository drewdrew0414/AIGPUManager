# Command Reference

All examples use dummy values. Replace IDs, hostnames, images, paths, URLs, and timestamps with your real environment values.

## Global Options

```bash
gpum --help
gpum --version
gpum --db data/example.db node list
gpum --config gpum.example.yaml system config
gpum --command-timeout-sec 30 system health
```

## Node

```bash
gpum node scan --force --discovery-depth 2
gpum node scan --ip 10.10.0.11 --ssh-user gpuadmin --force
gpum node scan --all --discovery-depth 1
gpum node list --sort gpu
gpum node info gpu-node-a
gpum node top --metric util
gpum node drain gpu-node-a --graceful --timeout 300 --reason "kernel patch" --evict
gpum node undrain gpu-node-a
gpum node maintenance gpu-node-a --on --reason "thermal inspection"
gpum node maintenance gpu-node-a --off
gpum node label gpu-node-a --set zone=nvlink,team=vision
gpum node label gpu-node-a --show
gpum node label gpu-node-a --remove zone,team
gpum node remote add --ip 10.10.0.12 --ssh-user gpuadmin --alias rack-a-02
gpum node remote list
gpum node remote remove --ip 10.10.0.12
```

## GPU

```bash
gpum gpu list
gpum gpu list --available --min-vram 80000 --capability mig --pci-gen 5
gpum gpu stats
gpum gpu stats --json
gpum gpu stats --export csv
gpum gpu stats --export influxdb
gpum gpu health --check-ecc --thermal-test --memory-test --report
gpum gpu health --score --quarantine-threshold 40
gpum gpu topology --visualize
gpum gpu set --id gpu-node-a:0 --power-limit 300
gpum gpu set --id gpu-node-a:0 --ecc on --allow-reboot-required
gpum gpu set --id gpu-node-a:0 --compute-mode exclusive_process
gpum gpu reset --id gpu-node-a:0 --soft --drain-first
GPUM_ENABLE_HARDWARE_WRITE=1 gpum gpu set --id gpu-node-a:0 --power-limit 300 --apply --approval-id appr-example
GPUM_ENABLE_HARDWARE_WRITE=1 gpum gpu reset --id gpu-node-a:0 --soft --drain-first --apply --approval-id appr-example
gpum gpu set --id gpu-node-b:0 --power-limit 280 --via-agent --ssh-user gpuadmin
```

## Allocation

```bash
gpum alloc estimate --model llama --params-b 13 --precision fp16 --context 8192 --batch 2
gpum alloc request --gpus 1 --vram 60000 --hours 4 --label-selector role=trainer --dry-run
gpum alloc request --gpus 2 --model H100 --vram 80000 --tenant research --priority 8 --affinity packed
gpum alloc request --gpus 4 --exclusive --preemptible --label-selector zone=nvlink
gpum alloc list
gpum alloc list --mine --status active
gpum alloc list --tenant research --node gpu-node-a
gpum alloc info --id alloc-example
gpum alloc extend --id alloc-example --hours 2 --reason "experiment still running"
gpum alloc move --id alloc-example --to-node gpu-node-b
gpum alloc release --id alloc-example
gpum alloc release --id alloc-example --kill-process
gpum alloc release --id alloc-example --force --kill-process
gpum alloc reap
```

## Compute

```bash
gpum compute quota --allocation-id alloc-example --cpu-cores 8 --memory-mb 65536 --pids 512
gpum compute quota --allocation-id alloc-example --cpu-cores 8 --memory-mb 65536 --pid 12345 --execute
gpum compute rdma --name ib-train --node gpu-node-a --device ib0 --bandwidth-mbit 100000 --priority 1
gpum compute rdma --name ib-train --node gpu-node-a --device ib0 --bandwidth-mbit 100000 --priority 1 --execute
gpum compute accelerator register --name edge-npu-a --kind npu --driver vendor-npu --endpoint gpu-node-a:/dev/npu0 --label team=vision
gpum compute accelerator list
gpum compute model-quota --name team-a-h100 --tenant team-a --gpu-model H100 --max-gpus 16
```

## Scheduling

```bash
gpum schedule queue create --name research --tenant research --weight 10 --max-gpus 32 --preemptible
gpum schedule queue list
gpum schedule reserve create --name nightly-ddp --queue research --start 2030-01-01T00:00:00Z --end 2030-01-01T04:00:00Z --gpus 8 --nodes 2 --project llm
gpum schedule reserve list
gpum schedule reserve cancel --id reservation-example
gpum schedule fair-share --owner alice --window-hours 168
gpum schedule gang --name ddp-8node --nodes 8 --gpus-per-node 8 --label-selector zone=nvlink --reserve
gpum schedule preempt --name urgent-train --victim-allocation-id alloc-low --incoming alloc-high --suspend-command "kill -STOP 12345" --resume-command "kill -CONT 12345"
gpum schedule preempt --name urgent-train --victim-allocation-id alloc-low --incoming alloc-high --execute
gpum schedule place --gpus 4 --vram 80000 --model H100 --strategy best-fit
gpum schedule place --gpus 4 --strategy worst-fit
gpum schedule place --gpus 4 --strategy topology
gpum schedule backfill --queue research --max-minutes 45 --max-gpus 2
```

## Data

```bash
gpum data cache --name imagenet-cache --source s3://example-bucket/imagenet --target D:\gpum-cache\imagenet
gpum data cache --name local-cache --source \\nfs\datasets\imagenet --target D:\gpum-cache\imagenet --execute
gpum data snapshot --name imagenet-v1 --source s3://example-bucket/imagenet --version v1 --mount D:\snapshots\imagenet-v1
gpum data checkpoint --name run42-ckpt --source D:\runs\run42\checkpoints --dest s3://example-bucket/checkpoints/run42
gpum data checkpoint --name run42-ckpt --source D:\runs\run42\checkpoints --dest D:\backup\run42 --execute
gpum data gds --name gds-read --mount D:\datasets --mode read
```

## Jobs

```bash
gpum job batch --name train-llm --allocation-id alloc-example --command "python train.py"
gpum job batch --name train-docker --allocation-id alloc-example --image nvcr.io/nvidia/pytorch:24.12-py3 --engine docker --gpus all --shm-size 128g --command "python train.py"
gpum job batch --name train-apptainer --allocation-id alloc-example --image train.sif --engine apptainer --command "python train.py"
gpum job batch --name train-singularity --allocation-id alloc-example --image train.sif --engine singularity --command "python train.py"
gpum job batch --name train-exec --allocation-id alloc-example --command "python train.py" --execute
gpum job session --name jupyter-a --allocation-id alloc-example --kind jupyter --port 8899
gpum job session --name vscode-a --allocation-id alloc-example --kind vscode --port 8898
gpum job session --name ssh-a --allocation-id alloc-example --kind ssh --port 2222
gpum job list
```

## Partitions

```bash
gpum part create --gpu gpu-node-a:0 --profile 1g.10gb --count 2
gpum part create --gpu gpu-node-a:0 --profile 2g.20gb --count 1 --apply --approval-id appr-example
gpum part list
gpum part destroy --id part-example
gpum part destroy --id part-example --apply --approval-id appr-example
gpum part auto-optimize
```

## Queue and Quota

```bash
gpum queue list
gpum queue list --full --position my --estimate
gpum queue promote --id queue-example --val 5
gpum queue demote --id queue-example --val 2
gpum quota set --name alice --max-gpus 4 --max-vram 320000 --max-lease-hours 72
gpum quota alert --name alice --threshold 80,90
gpum quota status --user alice --remaining
```

## Audit and Logs

```bash
gpum audit list --tail 50
gpum audit list --actor alice --action ALLOC_CREATE
gpum audit trace alloc-example
gpum log write --level info --component scheduler --category placement --message "placed job" --context alloc-example
gpum log list --component scheduler --contains placed --sort desc --limit 20
gpum log tail --lines 20
```

## Observability

```bash
gpum observe alert create --name slack-done --channel slack --target https://hooks.example/services/T000/B000/XXX --event job.done
gpum observe alert create --name email-error --channel email --target mlops@example.com --event job.error --template "gpum {{event}} {{resource}}"
gpum observe alert list
gpum observe profile --name profile-train --allocation-id alloc-example --tool nsys --command "python train.py"
gpum observe profile --name profile-kernel --allocation-id alloc-example --tool ncu --command "python train.py" --execute
gpum observe telemetry --name fast-gpu --interval-sec 5 --retention-hours 24
gpum observe telemetry --name snapshot --interval-sec 5 --retention-hours 24 --path D:\metrics\gpum.prom --execute
gpum observe log-stream --lines 50
gpum observe log-stream --component job --lines 100 --follow
```

## Integrations

```bash
gpum integration k8s contexts
gpum integration k8s pods --namespace research
gpum integration k8s submit --name trainer --image repo/train:latest --gpus 2 --namespace research --kind Job --allocation-id alloc-example
gpum integration k8s submit --name nightly --image repo/train:latest --kind CronJob --schedule "0 2 * * *" --env RUN_ID=example --secret-env WANDB_API_KEY=wandb --dataset-pvc imagenet-pvc --dataset-mount /datasets
gpum integration k8s logs trainer-pod-example --namespace research
gpum integration mlflow status
gpum integration mlflow runs --experiment llm --limit 20
gpum integration mlflow models --limit 20
gpum integration bentoml list
gpum integration bentoml models
gpum integration bentoml serve --bento fraud_detector:latest --port 3000
gpum integration ai env --allocation-id alloc-example --format shell
gpum integration ai env --allocation-id alloc-example --format json
gpum integration ai launch --allocation-id alloc-example --tool python --arg train.py --arg --epochs --arg 3
gpum integration ai launch --allocation-id alloc-example --tool python --from-file args.txt --via-ssh --ssh-user gpuadmin
gpum integration ai preset list
gpum integration ai preset render --allocation-id alloc-example --name torchrun-ddp --entrypoint train.py --arg --epochs --arg 3
gpum integration ai preset launch --allocation-id alloc-example --name accelerate --entrypoint train.py --execute
gpum integration tool --name ray --arg status
```

## Reports

```bash
gpum report usage --format json --by user
gpum report usage --format csv --by tenant
gpum report usage --format pdf --by model
gpum report billing --rate-card rate-card.yaml
gpum report prometheus
gpum report prometheus --path D:\metrics\gpum.prom
gpum report budget --name monthly-alice --owner alice --budget 1000 --rate-per-gpu-hour 3.5 --window-hours 720
gpum report cost-estimate --owner alice --gpu-model H100 --gpus 4 --hours 8 --rate-per-gpu-hour 3.5
```

## RBAC

```bash
gpum rbac whoami
gpum rbac role grant --actor alice --role admin
gpum rbac role grant --actor bob --role operator --tenant research
gpum rbac role list
gpum rbac role list --actor bob
gpum rbac role revoke --actor bob --role operator --tenant research
gpum rbac approval list
gpum rbac approval list --status pending --mine
gpum rbac approval approve --id appr-example --reason "maintenance approved"
gpum rbac approval deny --id appr-example --reason "outside maintenance window"
```

## Runtime

```bash
gpum runtime native metrics
gpum runtime worker register --id worker-a --allocation-id alloc-example --tenant research --owner alice --command "python train.py" --env WANDB_MODE=offline --checkpoint-command "python save.py" --restore-command "python restore.py" --max-restarts 3 --max-lifetime-min 1440 --memory-restart-mb 240000
gpum runtime worker list
gpum runtime worker start --id worker-a
gpum runtime worker stop --id worker-a
gpum runtime worker stop --id worker-a --force
gpum runtime worker restart --id worker-a --reason "manual retry"
gpum runtime worker restart --id worker-a --force --reason "hung dataloader"
gpum runtime worker recycle
gpum runtime worker recycle --execute
gpum runtime worker events --id worker-a --limit 50
gpum runtime daemon run --once
gpum runtime daemon run --interval-sec 30 --execute
gpum runtime oom handle --allocation-id alloc-example --strategy restart
gpum runtime oom handle --allocation-id alloc-example --strategy defrag --execute
gpum runtime oom handle --allocation-id alloc-example --strategy stop --execute
gpum runtime oom handle --allocation-id alloc-example --strategy release --execute
gpum runtime reconcile docker
gpum runtime reconcile k8s
gpum runtime migrate plan --worker-id worker-a --to-node gpu-node-b
gpum runtime migrate plan --worker-id worker-a --to-node gpu-node-b --execute
gpum runtime zombie list --gpu-id gpu-node-a:0
gpum runtime zombie clean --gpu-id gpu-node-a:0
gpum runtime zombie clean --gpu-id gpu-node-a:0 --force --execute
```

## Secrets and Developer Tools

```bash
gpum secret put --name wandb --provider env --ref WANDB_API_KEY --env WANDB_API_KEY
gpum secret put --name db-pass --provider vault --ref secret/mlops/db/password --env DB_PASSWORD
gpum secret list
gpum secret render --id ref-example --format shell
gpum secret render --id ref-example --format cmd
gpum secret render --id ref-example --format json
gpum dev completion --shell bash
gpum dev completion --shell zsh
gpum dev completion --shell powershell
gpum dev native
gpum dev terminal
gpum dev python-sdk --output generated/gpum_client.py
```

## Server

```bash
gpum server run --port 7070
gpum server run --port 0 --once
gpum server health --host 127.0.0.1 --port 7070
gpum server resources --host 127.0.0.1 --port 7070
gpum server allocate --host 127.0.0.1 --port 7070 --tenant research --owner alice --gpus 2 --model H100 --vram 80000 --hours 4 --affinity packed --dry-run
gpum server allocate --host 127.0.0.1 --port 7070 --tenant research --owner alice --gpus 2 --label-selector zone=nvlink --exclusive-node
gpum server submit --host 127.0.0.1 --port 7070 --allocation-id alloc-example --name train-remote --image nvcr.io/nvidia/pytorch:24.12-py3 --engine docker --gpus all --shm-size 128g --command "python train.py"
gpum server release --host 127.0.0.1 --port 7070 --id alloc-example
gpum server heartbeat --host 127.0.0.1 --port 7070 --node gpu-node-a --status ALIVE --labels zone=nvlink,team=research --allocatable-gpus 8
gpum server telemetry --host 127.0.0.1 --port 7070 --filter gpu
gpum server storage
gpum server lock --key alloc:H100:0 --owner scheduler --ttl-ms 30000
gpum server lock --key alloc:H100:0 --owner scheduler --release
```

## System and Safety

```bash
gpum system config
gpum system config --show-defaults
gpum system config --reload
gpum system config --edit
gpum system db-check
gpum system db-check --repair --vacuum --orphan-clean
gpum system health
gpum system safety limits
gpum system safety policy --max-gpus-per-request 16 --max-lease-hours 168 --thermal-warn-c 75 --thermal-critical-c 85 --min-free-vram-ratio 0.05 --max-power-limit-w 900 --min-disk-free-gb 20 --heartbeat-stale-sec 120 --max-job-shm-gb 512
gpum system safety check
gpum system safety check --quarantine
gpum system safety check --fail-on-warn
gpum system safety incident --node gpu-node-a --gpu-id 0 --severity warning --action quarantine --message "thermal anomaly"
gpum system safety incident --node gpu-node-a --severity critical --action drain --message "fan failure"
gpum system backup --path backups/gpum-backup.db
gpum system restore --path backups/gpum-backup.db
gpum system update
```

