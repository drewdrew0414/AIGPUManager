package com.drewdrew1.core.detector;

import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuVendor;

import java.util.List;

/** Carries detector output and non-fatal warnings for a single GPU vendor. */
public record DetectionResult(
        GpuVendor vendor,
        List<GpuDevice> devices,
        List<String> warnings
) {
}
