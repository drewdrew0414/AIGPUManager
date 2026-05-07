package com.drewdrew1.core.model;

/** Represents a single GPU assigned to an allocation. */
public record AllocationDevice(
        String nodeHostname,
        GpuVendor vendor,
        String deviceId,
        String uuid,
        String model,
        String pciBusId
) {
}
