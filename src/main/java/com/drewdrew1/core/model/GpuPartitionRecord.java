package com.drewdrew1.core.model;

import java.time.Instant;

/** Represents a logical partition recorded for a physical GPU device. */
public record GpuPartitionRecord(
        String id,
        String nodeHostname,
        String gpuDeviceId,
        String gpuModel,
        String profile,
        String status,
        Instant createdAt
) {
}
