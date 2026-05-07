package com.drewdrew1.core.model;

import java.time.Instant;

/** Represents a queued allocation request that could not be placed immediately. */
public record QueueEntry(
        String id,
        String owner,
        String tenant,
        int gpuCount,
        String modelFilter,
        Long minVramMb,
        boolean exclusiveNode,
        int requestedHours,
        int priority,
        boolean preemptible,
        AllocationAffinity affinity,
        String labelSelector,
        String status,
        String reason,
        Instant createdAt,
        Instant updatedAt
) {
}
