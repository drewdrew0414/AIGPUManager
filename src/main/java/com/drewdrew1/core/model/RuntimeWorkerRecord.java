package com.drewdrew1.core.model;

import java.time.Instant;
import java.util.Map;

/** Stores a managed AI worker process definition and its latest runtime state. */
public record RuntimeWorkerRecord(
        String id,
        String allocationId,
        String tenant,
        String owner,
        String command,
        String workingDirectory,
        Map<String, String> environment,
        String checkpointCommand,
        String restoreCommand,
        String oomRecoveryCommand,
        Long pid,
        String status,
        int restartCount,
        int maxRestarts,
        int maxLifetimeMinutes,
        Long memoryRestartMb,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt
) {
}
