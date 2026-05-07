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

        NvidiaDetector detector = new NvidiaDetector(executor, new CapabilityResolver());
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
}
