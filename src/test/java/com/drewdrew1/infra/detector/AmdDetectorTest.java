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

        AmdDetector detector = new AmdDetector(executor, new CapabilityResolver(), "amd-smi", "rocm-smi");
        DetectionResult result = detector.detect("node-b", Instant.parse("2026-05-07T00:00:00Z"));

        assertEquals(1, result.devices().size());
        GpuDevice gpu = result.devices().get(0);
        assertEquals("AMD Instinct MI300X", gpu.model());
        assertEquals(205520L, gpu.vramTotalMb());
        assertEquals(139984L, gpu.vramFreeMb());
        assertEquals(InterconnectType.XGMI, gpu.interconnectType());
        assertTrue(Boolean.TRUE.equals(gpu.eccEnabled()));
    }

    @Test
    void parsesRepresentativeAmdFamiliesFromAmdSmiOutputs() {
        FakeCommandExecutor executor = new FakeCommandExecutor()
                .addSuccess(List.of("amd-smi", "list", "--json"), """
                        {
                          "gpu_list": [
                            {"gpu": 0, "gpu_id": "0", "product_name": "AMD Instinct MI210", "uuid": "GPU-MI210", "pci_bdf": "0000:81:00.0"},
                            {"gpu": 1, "gpu_id": "1", "product_name": "AMD Instinct MI250X", "uuid": "GPU-MI250X", "pci_bdf": "0000:82:00.0"},
                            {"gpu": 2, "gpu_id": "2", "product_name": "AMD Instinct MI300X", "uuid": "GPU-MI300X", "pci_bdf": "0000:83:00.0"},
                            {"gpu": 3, "gpu_id": "3", "product_name": "AMD Radeon PRO W7900", "uuid": "GPU-W7900", "pci_bdf": "0000:84:00.0"}
                          ]
                        }
                        """)
                .addSuccess(List.of("amd-smi", "static", "--json"), """
                        {
                          "gpu_static": [
                            {"gpu": 0, "memory_physical_size": "65536 MiB", "ecc_mode": "enabled"},
                            {"gpu": 1, "memory_physical_size": "131072 MiB", "ecc_mode": "enabled"},
                            {"gpu": 2, "memory_physical_size": "196608 MiB", "ecc_mode": "enabled"},
                            {"gpu": 3, "memory_physical_size": "49152 MiB", "ecc_mode": "disabled"}
                          ]
                        }
                        """)
                .addSuccess(List.of("amd-smi", "metric", "--json"), """
                        {
                          "gpu_metrics": [
                            {"gpu": 0, "vram_used": "2048 MiB", "gpu_use": "27", "temperature": "47", "power": "250"},
                            {"gpu": 1, "vram_used": "4096 MiB", "gpu_use": "19", "temperature": "44", "power": "410"},
                            {"gpu": 2, "vram_used": "1024 MiB", "gpu_use": "23", "temperature": "40", "power": "520"},
                            {"gpu": 3, "vram_used": "2048 MiB", "gpu_use": "6", "temperature": "42", "power": "190"}
                          ]
                        }
                        """)
                .addSuccess(List.of("amd-smi", "xgmi", "--json"), """
                        {
                          "gpu_xgmi": [
                            {"gpu": 0, "xgmi_link": "enabled"},
                            {"gpu": 1, "xgmi_link": "enabled"},
                            {"gpu": 2, "xgmi_link": "enabled"},
                            {"gpu": 3, "xgmi_link": "disabled"}
                          ]
                        }
                        """);

        DetectionResult result = new AmdDetector(executor, new CapabilityResolver(), "amd-smi", "rocm-smi")
                .detect("amd-lab", Instant.parse("2026-05-07T00:00:00Z"));

        assertEquals(4, result.devices().size());
        assertTrue(result.devices().stream().anyMatch(device -> device.model().contains("MI210") && device.interconnectType() == InterconnectType.XGMI));
        assertTrue(result.devices().stream().anyMatch(device -> device.model().contains("MI250X") && device.vramTotalMb() == 131072L));
        assertTrue(result.devices().stream().anyMatch(device -> device.model().contains("MI300X") && device.vramTotalMb() == 196608L));
        assertTrue(result.devices().stream().anyMatch(device -> device.model().contains("W7900") && device.interconnectType() == InterconnectType.PCIE));
    }
}
