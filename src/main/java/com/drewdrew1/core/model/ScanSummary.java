package com.drewdrew1.core.model;

import java.util.List;
import java.util.Set;

/** Summarizes the result of a node inventory scan. */
public record ScanSummary(
        NodeInventory node,
        int discoveredGpuCount,
        Set<GpuVendor> discoveredVendors,
        List<String> warnings
) {
}
