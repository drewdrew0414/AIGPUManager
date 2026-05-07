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

/** Verifies AMD detector parsing and normalization behavior. */
class AmdDetectorTest {
    @Test
    void parsesAmdInventoryIntoCommonGpuDeviceModel() {
        FakeCommandExecutor executor = new FakeCommandExecutor()
                .addSuccess(List.of("amd-smi", "list", "--json"), """
                        {
                          "gpu_list": [
                            {
                              "gpu": 0,
                              "product_name": "AMD Instinct MI300X",
                              "uuid": "AMD-123",
                              "pci_bdf": "0000:C1:00.0"
                            }
                          ]
                        }
                        """)
                .addSuccess(List.of("amd-smi", "static", "--json"), """
                        {
                          "gpu_static": [
                            {
                              "gpu": 0,
                              "memory_physical_size": "205520 MiB",
                              "driver_version": "6.1.0",
                              "ecc_state": "enabled"
                            }
                          ]
                        }
                        """)
                .addSuccess(List.of("amd-smi", "metric", "--json"), """
                        {
                          "gpu_metrics": [
                            {
                              "gpu": 0,
                              "gpu_use": "61",
                              "temperature": "54.0 C",
                              "power": "510.0 W",
                              "vram_used_memory_b": "68719476736"
                            }
                          ]
                        }
                        """)
                .addSuccess(List.of("amd-smi", "xgmi", "--json"), """
                        {
                          "gpu_xgmi": [
                            {
                              "gpu": 0,
                              "xgmi_links": "8"
                            }
                          ]
                        }
                        """);

        AmdDetector detector = new AmdDetector(executor, new CapabilityResolver());
        DetectionResult result = detector.detect("node-b", Instant.parse("2026-05-07T00:00:00Z"));

        assertEquals(1, result.devices().size());
        GpuDevice gpu = result.devices().get(0);
        assertEquals("AMD Instinct MI300X", gpu.model());
        assertEquals(205520L, gpu.vramTotalMb());
        assertEquals(139984L, gpu.vramFreeMb());
        assertEquals(InterconnectType.XGMI, gpu.interconnectType());
        assertTrue(Boolean.TRUE.equals(gpu.eccEnabled()));
    }
}
