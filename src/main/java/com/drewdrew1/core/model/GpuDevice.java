package com.drewdrew1.core.model;

import java.time.Instant;

/** Represents the normalized view of a GPU device across all vendors. */
public record GpuDevice(
        String nodeHostname,
        GpuVendor vendor,
        String deviceId,
        String model,
        String uuid,
        String pciBusId,
        String driverVersion,
        Long vramTotalMb,
        Long vramFreeMb,
        Double utilizationGpu,
        Double utilizationMemory,
        Double temperatureC,
        Double powerUsageW,
        Double powerLimitW,
        Boolean eccEnabled,
        InterconnectType interconnectType,
        HealthState healthState,
        boolean integratedGraphics,
        boolean sharedSystemMemory,
        Long sharedMemoryTotalMb,
        boolean supportsMig,
        boolean supportsPartitioning,
        boolean supportsCompute,
        boolean supportsContainerRuntime,
        Instant lastScannedAt
) {
}
