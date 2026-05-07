package com.drewdrew1.core.model;

import java.time.Instant;
import java.util.List;

/** Represents the scheduler outcome for a request before or during persistence. */
public record AllocationDecision(
        AllocationRequest request,
        List<AllocationDevice> devices,
        String primaryNodeHostname,
        Instant createdAt,
        Instant expiresAt,
        boolean dryRun
) {
}
