package com.drewdrew1.core.model;

import java.util.List;
import java.util.Set;

public record ScanSummary(
        NodeInventory node,
        int discoveredGpuCount,
        Set<GpuVendor> discoveredVendors,
        List<String> warnings
) {
}
