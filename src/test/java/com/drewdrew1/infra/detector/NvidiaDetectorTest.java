package com.drewdrew1.infra.detector;

import com.drewdrew1.core.detector.DetectionResult;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.InterconnectType;
import com.drewdrew1.core.service.CapabilityResolver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies NVIDIA detector parsing and NVLink capability resolution. */
class NvidiaDetectorTest {
    @Test
    void parsesNvidiaInventoryAndNvlinkCapabilities() {
        FakeCommandExecutor executor = new FakeCommandExecutor()
                .addSuccess(List.of(
                                "nvidia-smi",
                                "--query-gpu=index,name,uuid,pci.bus_id,driver_version,memory.total,memory.free,utilization.gpu,utilization.memory,temperature.gpu,power.draw,power.limit,ecc.mode.current,mig.mode.current",
                                "--format=csv,noheader,nounits"
                        ),
                        """
                        0, NVIDIA H100 80GB HBM3, GPU-123, 0000:17:00.0, 550.54.15, 81480, 80000, 17, 5, 41, 126.5, 700.0, Enabled, Enabled
                        1, NVIDIA RTX 6000 Ada, GPU-456, 0000:65:00.0, 550.54.15, 49140, 47000, 2, 1, 37, 42.0, 300.0, Disabled, N/A
                        """)
                .addSuccess(List.of("nvidia-smi", "topo", "-m"), """
                                GPU0 GPU1 CPU Affinity
                        GPU0    X   NV4 0-47
                        GPU1   NV4  X   0-47
                        """);

        NvidiaDetector detector = new NvidiaDetector(executor, new CapabilityResolver(), "nvidia-smi");
        DetectionResult result = detector.detect("node-a", Instant.parse("2026-05-07T00:00:00Z"));

        assertTrue(result.warnings().isEmpty());
        assertEquals(2, result.devices().size());

        GpuDevice h100 = result.devices().get(0);
        assertEquals("NVIDIA H100 80GB HBM3", h100.model());
        assertEquals(81480L, h100.vramTotalMb());
        assertEquals(InterconnectType.NVLINK, h100.interconnectType());
        assertTrue(h100.supportsMig());

        GpuDevice rtx = result.devices().get(1);
        assertFalse(rtx.supportsMig());
        assertEquals(InterconnectType.NVLINK, rtx.interconnectType());
    }

    @Test
    void parsesRepresentativeNvidiaFamiliesFromSingleInventorySnapshot() {
        FakeCommandExecutor executor = new FakeCommandExecutor()
                .addSuccess(List.of(
                                "nvidia-smi",
                                "--query-gpu=index,name,uuid,pci.bus_id,driver_version,memory.total,memory.free,utilization.gpu,utilization.memory,temperature.gpu,power.draw,power.limit,ecc.mode.current,mig.mode.current",
                                "--format=csv,noheader,nounits"
                        ),
                        """
                        0, NVIDIA H100 80GB HBM3, GPU-H100, 0000:17:00.0, 550.54.15, 81480, 79000, 15, 8, 42, 210, 700, Enabled, Enabled
                        1, NVIDIA H200 141GB HBM3e, GPU-H200, 0000:18:00.0, 550.54.15, 141312, 140000, 12, 7, 41, 240, 700, Enabled, Enabled
                        2, NVIDIA B200, GPU-B200, 0000:19:00.0, 560.10.01, 183296, 182000, 10, 6, 39, 255, 1000, Enabled, Enabled
                        3, NVIDIA A100 80GB PCIe, GPU-A100, 0000:1A:00.0, 550.54.15, 81480, 78000, 18, 9, 44, 205, 400, Enabled, Enabled
                        4, NVIDIA RTX 4090, GPU-RTX4090, 0000:65:00.0, 550.54.15, 24564, 22000, 9, 4, 48, 280, 450, Disabled, N/A
                        5, NVIDIA RTX 6000 Ada Generation, GPU-RTX6000ADA, 0000:66:00.0, 550.54.15, 49140, 47000, 7, 3, 43, 250, 300, Disabled, N/A
                        """)
                .addSuccess(List.of("nvidia-smi", "topo", "-m"), """
                        GPU0 GPU1 GPU2 GPU3 GPU4 GPU5 CPU Affinity
                        GPU0 X NV4 NV4 NV2 PHB PHB 0-63
                        GPU1 NV4 X NV4 NV2 PHB PHB 0-63
                        GPU2 NV4 NV4 X NV2 PHB PHB 0-63
                        GPU3 NV2 NV2 NV2 X PHB PHB 0-63
                        GPU4 PHB PHB PHB PHB X SYS 64-127
                        GPU5 PHB PHB PHB PHB SYS X 64-127
                        """);

        DetectionResult result = new NvidiaDetector(executor, new CapabilityResolver(), "nvidia-smi")
                .detect("nvidia-lab", Instant.parse("2026-05-07T00:00:00Z"));

        assertEquals(6, result.devices().size());
        assertTrue(result.devices().stream().anyMatch(device -> device.model().contains("H100") && device.supportsMig()));
        assertTrue(result.devices().stream().anyMatch(device -> device.model().contains("H200") && device.supportsMig()));
        assertTrue(result.devices().stream().anyMatch(device -> device.model().contains("B200") && device.supportsMig()));
        assertTrue(result.devices().stream().anyMatch(device -> device.model().contains("A100") && device.interconnectType() == InterconnectType.NVLINK));
        assertTrue(result.devices().stream().anyMatch(device -> device.model().contains("RTX 4090") && !device.supportsMig()));
        assertTrue(result.devices().stream().anyMatch(device -> device.model().contains("RTX 6000 Ada") && !device.supportsMig()));
    }
}
