package com.drewdrew1.core.model;

import java.time.Instant;

/** Represents the normalized view of a compute node inventory snapshot. */
public record NodeInventory(
        String hostname,
        String osName,
        String osArch,
        int cpuCores,
        long memoryTotalMb,
        Instant lastScannedAt
) {
}
