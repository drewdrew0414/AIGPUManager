package com.drewdrew1.core.model;

import java.util.List;

/** Represents a computed operational health score for one GPU. */
public record GpuHealthScore(
        String nodeHostname,
        String deviceId,
        String uuid,
        String model,
        GpuVendor vendor,
        double score,
        boolean degraded,
        boolean quarantineRecommended,
        List<String> reasons
) {
}
