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

/** Verifies Intel detector parsing and normalization behavior. */
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

        IntelDetector detector = new IntelDetector(executor, new CapabilityResolver(), "xpu-smi");
        DetectionResult result = detector.detect("node-c", Instant.parse("2026-05-07T00:00:00Z"));

        assertEquals(1, result.devices().size());
        GpuDevice gpu = result.devices().get(0);
        assertEquals("Intel Data Center GPU Max 1550", gpu.model());
        assertEquals(65536L, gpu.vramTotalMb());
        assertEquals(49152L, gpu.vramFreeMb());
        assertEquals(InterconnectType.XE_LINK, gpu.interconnectType());
        assertTrue(Boolean.TRUE.equals(gpu.eccEnabled()));
    }

    @Test
    void parsesRepresentativeIntelFamiliesFromXpuSmiOutputs() {
        FakeCommandExecutor executor = new FakeCommandExecutor()
                .addSuccess(List.of("xpu-smi", "discovery", "-j"), """
                        {
                          "device_list": [
                            {"device_id": 0, "device_name": "Intel Data Center GPU Max 1100", "uuid": "GPU-MAX1100", "pci_bdf_address": "0000:91:00.0", "memory_physical_size": "51539607552"},
                            {"device_id": 1, "device_name": "Intel Data Center GPU Max 1550", "uuid": "GPU-MAX1550", "pci_bdf_address": "0000:92:00.0", "memory_physical_size": "68719476736"},
                            {"device_id": 2, "device_name": "Intel Arc Pro A60", "uuid": "GPU-ARCPROA60", "pci_bdf_address": "0000:93:00.0", "memory_physical_size": "12884901888"},
                            {"device_id": 3, "device_name": "Intel Data Center GPU Flex 170", "uuid": "GPU-FLEX170", "pci_bdf_address": "0000:94:00.0", "memory_physical_size": "17179869184"}
                          ]
                        }
                        """)
                .addSuccess(List.of("xpu-smi", "discovery", "-d", "0", "-j"), "{\"device_list\":[{\"device_id\":\"0\",\"driver_version\":\"1.5.0\",\"xe_link\":\"enabled\"}]}")
                .addSuccess(List.of("xpu-smi", "stats", "-d", "0", "-j"), "{\"device_list\":[{\"device_id\":\"0\",\"gpu_memory_used\":\"2147483648\",\"gpu_utilization\":\"16\",\"gpu_core_temperature\":\"39\",\"gpu_power\":\"240\"}]}")
                .addSuccess(List.of("xpu-smi", "health", "-d", "0", "-j"), "{\"device_list\":[{\"device_id\":\"0\",\"ecc_state\":\"enabled\",\"health\":\"ok\"}]}")
                .addSuccess(List.of("xpu-smi", "discovery", "-d", "1", "-j"), "{\"device_list\":[{\"device_id\":\"1\",\"driver_version\":\"1.5.0\",\"xe_link\":\"enabled\"}]}")
                .addSuccess(List.of("xpu-smi", "stats", "-d", "1", "-j"), "{\"device_list\":[{\"device_id\":\"1\",\"gpu_memory_used\":\"4294967296\",\"gpu_utilization\":\"15\",\"gpu_core_temperature\":\"40\",\"gpu_power\":\"275\"}]}")
                .addSuccess(List.of("xpu-smi", "health", "-d", "1", "-j"), "{\"device_list\":[{\"device_id\":\"1\",\"ecc_state\":\"enabled\",\"health\":\"ok\"}]}")
                .addSuccess(List.of("xpu-smi", "discovery", "-d", "2", "-j"), "{\"device_list\":[{\"device_id\":\"2\",\"driver_version\":\"1.5.0\",\"xe_link\":\"disabled\"}]}")
                .addSuccess(List.of("xpu-smi", "stats", "-d", "2", "-j"), "{\"device_list\":[{\"device_id\":\"2\",\"gpu_memory_used\":\"2147483648\",\"gpu_utilization\":\"5\",\"gpu_core_temperature\":\"45\",\"gpu_power\":\"130\"}]}")
                .addSuccess(List.of("xpu-smi", "health", "-d", "2", "-j"), "{\"device_list\":[{\"device_id\":\"2\",\"ecc_state\":\"disabled\",\"health\":\"ok\"}]}")
                .addSuccess(List.of("xpu-smi", "discovery", "-d", "3", "-j"), "{\"device_list\":[{\"device_id\":\"3\",\"driver_version\":\"1.5.0\",\"xe_link\":\"enabled\"}]}")
                .addSuccess(List.of("xpu-smi", "stats", "-d", "3", "-j"), "{\"device_list\":[{\"device_id\":\"3\",\"gpu_memory_used\":\"1073741824\",\"gpu_utilization\":\"8\",\"gpu_core_temperature\":\"41\",\"gpu_power\":\"150\"}]}")
                .addSuccess(List.of("xpu-smi", "health", "-d", "3", "-j"), "{\"device_list\":[{\"device_id\":\"3\",\"ecc_state\":\"disabled\",\"health\":\"ok\"}]}");

        DetectionResult result = new IntelDetector(executor, new CapabilityResolver(), "xpu-smi")
                .detect("intel-lab", Instant.parse("2026-05-07T00:00:00Z"));

        assertEquals(4, result.devices().size());
        assertTrue(result.devices().stream().anyMatch(device -> device.model().contains("Max 1100") && device.interconnectType() == InterconnectType.XE_LINK));
        assertTrue(result.devices().stream().anyMatch(device -> device.model().contains("Max 1550") && device.vramTotalMb() == 65536L));
        assertTrue(result.devices().stream().anyMatch(device -> device.model().contains("Arc Pro A60") && device.interconnectType() == InterconnectType.PCIE));
        assertTrue(result.devices().stream().anyMatch(device -> device.model().contains("Flex 170") && device.interconnectType() == InterconnectType.XE_LINK));
    }
}
