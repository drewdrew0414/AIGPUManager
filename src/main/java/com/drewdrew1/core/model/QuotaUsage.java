package com.drewdrew1.core.model;

/** Represents current quota usage and remaining capacity for one target. */
public record QuotaUsage(
        String name,
        Integer maxGpus,
        Long maxVramMb,
        Integer maxLeaseHours,
        boolean burstAllow,
        int activeGpuCount,
        long activeVramMb,
        int activeLeaseHours,
        Integer remainingGpus,
        Long remainingVramMb,
        Integer remainingLeaseHours
) {
}
