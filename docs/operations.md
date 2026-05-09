# Operational Notes

## Execution Flags

- `--execute` runs external commands. Without it, most integration and orchestration commands only print a plan.
- `--apply` is for guarded hardware mutation and still requires role, approval, and `GPUM_ENABLE_HARDWARE_WRITE=1`.
- `--dry-run` is available where placement or allocation can be simulated before persistence.

## Safety Policy

`system safety policy` stores limits in `ops_records`. Allocation and server allocation read these limits.

`job batch` validates execution engine and shared-memory size against safety policy.

`job batch`, `job session`, `observe profile`, `compute quota`, and `schedule preempt` validate referenced allocation IDs before recording or executing dependent work.

## Storage

SQLite is the default local persistence path:

```text
data/gpu-mgr.db
```

Optional server backend readiness checks use:

```bash
export GPUM_POSTGRES_URL=jdbc:postgresql://localhost:5432/gpum
export GPUM_POSTGRES_USER=gpum
export GPUM_POSTGRES_PASSWORD=example-password
export GPUM_REDIS_URL=redis://localhost:6379
gpum server storage
```

## Version

Release-facing version identifiers are aligned to `1.1.0`:

- Gradle project version: `1.1.0`
- CLI version output: `gpum 1.1.0`
- generated distribution version file: `1.1.0`
- release note title: `gpum v1.1.0`

## gRPC Protocol

```text
src/main/proto/gpum.proto
```

The service is `gpum.v1.GpumControl`.

