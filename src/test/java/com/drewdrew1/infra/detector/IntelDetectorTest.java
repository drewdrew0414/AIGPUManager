package com.drewdrew1.infra.detector;

import com.drewdrew1.core.detector.DetectionResult;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.InterconnectType;
import com.drewdrew1.core.service.CapabilityResolver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelDetectorTest {
    @Test
    void parsesIntelInventoryUsingXpuSmiOutputs() {
        FakeCommandExecutor executor = new FakeCommandExecutor()
                .addSuccess(List.of("xpu-smi", "discovery", "-j"), """
                        {
                          "device_list": [
                            {
                              "device_id": 0,
                              "device_name": "Intel Data Center GPU Max 1550",
                              "pci_bdf_address": "0000:4d:00.0",
                              "uuid": "INTEL-123"
                            }
                          ]
                        }
                        """)
                .addSuccess(List.of("xpu-smi", "discovery", "-d", "0", "-j"), """
                        {
                          "device_id": 0,
                          "device_name": "Intel Data Center GPU Max 1550",
                          "driver_version": "1.3.30510",
                          "memory_physical_size": "65536 MiB",
                          "ecc_state": "enabled",
                          "number_of_xe_link_ports": "16"
                        }
                        """)
                .addSuccess(List.of("xpu-smi", "stats", "-d", "0", "-j"), """
                        {
                          "device_id": 0,
                          "gpu_utilization": "27",
                          "gpu_power": "420.0 W",
                          "gpu_core_temperature": "49 C",
                          "gpu_memory_used": "17179869184"
                        }
                        """)
                .addSuccess(List.of("xpu-smi", "health", "-d", "0", "-j"), """
                        {
                          "device_id": 0,
                          "health_status": "OK"
                        }
                        """);

        IntelDetector detector = new IntelDetector(executor, new CapabilityResolver());
        DetectionResult result = detector.detect("node-c", Instant.parse("2026-05-07T00:00:00Z"));

        assertEquals(1, result.devices().size());
        GpuDevice gpu = result.devices().get(0);
        assertEquals("Intel Data Center GPU Max 1550", gpu.model());
        assertEquals(65536L, gpu.vramTotalMb());
        assertEquals(49152L, gpu.vramFreeMb());
        assertEquals(InterconnectType.XE_LINK, gpu.interconnectType());
        assertTrue(Boolean.TRUE.equals(gpu.eccEnabled()));
    }
}
