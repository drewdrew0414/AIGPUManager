package com.drewdrew1.core.detector;

import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuVendor;

import java.util.List;

public record DetectionResult(
        GpuVendor vendor,
        List<GpuDevice> devices,
        List<String> warnings
) {
}
