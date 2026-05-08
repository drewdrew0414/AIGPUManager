package com.drewdrew1.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Ensures optional native telemetry degrades cleanly when NVML or Level Zero are absent. */
class NativeGpuTelemetryServiceTest {
    @Test
    void collectNeverRequiresGpuHardware() {
        NativeGpuTelemetryService service = new NativeGpuTelemetryService();

        assertFalse(service.collect().isEmpty());
    }
}
