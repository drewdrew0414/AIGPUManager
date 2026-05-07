package com.drewdrew1.core.model;

import java.time.Instant;

/** Represents a persisted quota policy for a user, tenant, or generic target. */
public record QuotaPolicy(
        String name,
        Integer maxGpus,
        Long maxVramMb,
        Integer maxLeaseHours,
        boolean burstAllow,
        Instant updatedAt
) {
}
