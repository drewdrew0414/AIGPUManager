package com.drewdrew1.core.model;

/** Represents one aggregated row in usage or billing reports. */
public record UsageReportRow(
        String key,
        int allocationCount,
        int gpuCount,
        long totalVramMb,
        long totalLeaseHours,
        double gpuHours,
        double estimatedCost
) {
}
