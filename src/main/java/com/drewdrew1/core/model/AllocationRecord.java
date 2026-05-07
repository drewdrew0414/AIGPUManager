package com.drewdrew1.core.model;

import java.time.Instant;
import java.util.List;

/** Stores a persisted allocation together with its assigned devices. */
public record AllocationRecord(
        String id,
        String owner,
        String tenant,
        AllocationStatus status,
        boolean exclusiveNode,
        int priority,
        boolean preemptible,
        AllocationAffinity affinity,
        int requestedGpuCount,
        String modelFilter,
        Long minVramMb,
        String labelSelector,
        String primaryNodeHostname,
        Instant createdAt,
        Instant expiresAt,
        Instant releasedAt,
        List<AllocationDevice> devices
) {
}
