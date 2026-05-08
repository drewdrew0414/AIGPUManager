package com.drewdrew1.core.service;

import com.drewdrew1.core.config.GpumConfig;
import com.drewdrew1.core.model.AllocationAffinity;
import com.drewdrew1.core.model.AllocationDevice;
import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.AllocationStatus;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.core.model.HealthState;
import com.drewdrew1.core.model.InterconnectType;
import com.drewdrew1.core.model.NodeInventory;
import com.drewdrew1.infra.persistence.SqliteAllocationRepository;
import com.drewdrew1.infra.persistence.SqliteInventoryRepository;
import com.drewdrew1.testsupport.RecordingCommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies guarded GPU write operations and physical-safety interlocks. */
class GpuControlServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void appliesNvidiaPowerLimitOnlyAfterSafetyGatesPass() {
        System.setProperty("gpum.enableHardwareWrite", "true");
        try {
            Path dbPath = tempDir.resolve("gpu-control.db");
            SqliteInventoryRepository inventoryRepository = new SqliteInventoryRepository(dbPath);
            SqliteAllocationRepository allocationRepository = new SqliteAllocationRepository(dbPath);
            Instant now = Instant.parse("2026-05-08T00:00:00Z");
            inventoryRepository.saveNode(new NodeInventory("local-node", "Linux", "amd64", 32, 131072, now));
            GpuDevice gpu = gpu("local-node", "0", "GPU-0", "NVIDIA H100 80GB HBM3", InterconnectType.PCIE, false);
            inventoryRepository.replaceNodeGpus("local-node", List.of(gpu));

            RecordingCommandExecutor executor = new RecordingCommandExecutor()
                    .addSuccess(List.of("nvidia-smi", "-q", "-d", "POWER", "-i", "GPU-0"), """
                            Power Readings
                                Min Power Limit                    : 200 W
                                Max Power Limit                    : 700 W
                            """)
                    .addSuccess(List.of("nvidia-smi", "-i", "GPU-0", "-pl", "350"), "Power limit for GPU 0000 updated.");

            GpuControlService service = new GpuControlService(
                    executor,
                    inventoryRepository,
                    allocationRepository,
                    new FixedSystemInfoService("local-node", now),
                    new GpumConfig()
            );

            GpuControlService.ControlResult result = service.applySettings(
                    gpu,
                    new GpuControlService.SetRequest(true, false, false, 350, null, null, null)
            );

            assertEquals(2, executor.executed().size());
            assertEquals(GpuVendor.NVIDIA, result.vendor());
            assertTrue(result.outputs().getLast().contains("updated"));
        } finally {
            System.clearProperty("gpum.enableHardwareWrite");
        }
    }

    @Test
    void blocksResetWhenGpuHasActiveAllocation() {
        System.setProperty("gpum.enableHardwareWrite", "true");
        try {
            Path dbPath = tempDir.resolve("gpu-reset.db");
            SqliteInventoryRepository inventoryRepository = new SqliteInventoryRepository(dbPath);
            SqliteAllocationRepository allocationRepository = new SqliteAllocationRepository(dbPath);
            Instant now = Instant.parse("2026-05-08T00:00:00Z");
            inventoryRepository.saveNode(new NodeInventory("local-node", "Linux", "amd64", 32, 131072, now));
            GpuDevice gpu = gpu("local-node", "0", "GPU-0", "NVIDIA H100 80GB HBM3", InterconnectType.PCIE, false);
            inventoryRepository.replaceNodeGpus("local-node", List.of(gpu));
            allocationRepository.create(new AllocationRecord(
                    "alloc-1",
                    "tester",
                    null,
                    AllocationStatus.ACTIVE,
                    false,
                    5,
                    false,
                    AllocationAffinity.PACKED,
                    1,
                    "H100",
                    80000L,
                    null,
                    "local-node",
                    now,
                    now.plusSeconds(3600),
                    null,
                    List.of(new AllocationDevice("local-node", GpuVendor.NVIDIA, "0", "GPU-0", "NVIDIA H100 80GB HBM3", "0000:17:00.0"))
            ));

            GpuControlService service = new GpuControlService(
                    new RecordingCommandExecutor(),
                    inventoryRepository,
                    allocationRepository,
                    new FixedSystemInfoService("local-node", now),
                    new GpumConfig()
            );

            assertThrows(IllegalArgumentException.class, () -> service.resetGpu(
                    gpu,
                    new GpuControlService.ResetRequest(true, false, false)
            ));
        } finally {
            System.clearProperty("gpum.enableHardwareWrite");
        }
    }

    @Test
    void blocksHardwareWriteWithoutSafetySwitch() {
        Path dbPath = tempDir.resolve("gpu-no-switch.db");
        SqliteInventoryRepository inventoryRepository = new SqliteInventoryRepository(dbPath);
        SqliteAllocationRepository allocationRepository = new SqliteAllocationRepository(dbPath);
        Instant now = Instant.parse("2026-05-08T00:00:00Z");
        inventoryRepository.saveNode(new NodeInventory("local-node", "Linux", "amd64", 32, 131072, now));
        GpuDevice gpu = gpu("local-node", "0", "GPU-0", "NVIDIA H100 80GB HBM3", InterconnectType.PCIE, false);
        inventoryRepository.replaceNodeGpus("local-node", List.of(gpu));

        GpuControlService service = new GpuControlService(
                new RecordingCommandExecutor(),
                inventoryRepository,
                allocationRepository,
                new FixedSystemInfoService("local-node", now),
                new GpumConfig()
        );

        assertThrows(IllegalArgumentException.class, () -> service.applySettings(
                gpu,
                new GpuControlService.SetRequest(true, false, false, 350, null, null, null)
        ));
    }

    private GpuDevice gpu(String node, String deviceId, String uuid, String model, InterconnectType interconnectType, boolean integrated) {
        return new GpuDevice(
                node,
                GpuVendor.NVIDIA,
                deviceId,
                model,
                uuid,
                "0000:17:00.0",
                "550.54.15",
                81480L,
                80000L,
                5.0,
                1.0,
                40.0,
                250.0,
                700.0,
                true,
                interconnectType,
                HealthState.OK,
                integrated,
                integrated,
                integrated ? 16384L : null,
                false,
                false,
                true,
                true,
                Instant.parse("2026-05-08T00:00:00Z")
        );
    }

    private static final class FixedSystemInfoService extends SystemInfoService {
        private final String hostname;
        private final Instant scannedAt;

        private FixedSystemInfoService(String hostname, Instant scannedAt) {
            this.hostname = hostname;
            this.scannedAt = scannedAt;
        }

        @Override
        public NodeInventory localNodeInventory() {
            return new NodeInventory(hostname, "Linux", "amd64", 32, 131072, scannedAt);
        }
    }
}
