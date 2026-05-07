package com.drewdrew1.core.model;

import java.time.Instant;

public record NodeInventory(
        String hostname,
        String osName,
        String osArch,
        int cpuCores,
        long memoryTotalMb,
        Instant lastScannedAt
) {
}
