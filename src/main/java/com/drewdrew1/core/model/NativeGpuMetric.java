package com.drewdrew1.core.model;

/** Represents one metric sample collected through an optional native GPU binding. */
public record NativeGpuMetric(
        GpuVendor vendor,
        int index,
        String name,
        Long memoryTotalMb,
        Long memoryUsedMb,
        Double utilizationGpu,
        String source,
        boolean available,
        String warning
) {
}
