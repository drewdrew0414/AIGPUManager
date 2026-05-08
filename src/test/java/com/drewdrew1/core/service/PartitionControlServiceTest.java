package com.drewdrew1.core.service;

import com.drewdrew1.core.config.GpumConfig;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuPartitionRecord;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.core.model.HealthState;
import com.drewdrew1.core.model.InterconnectType;
import com.drewdrew1.core.model.NodeInventory;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;
import com.drewdrew1.infra.persistence.SqliteAllocationRepository;
import com.drewdrew1.infra.persistence.SqliteGovernanceRepository;
import com.drewdrew1.infra.persistence.SqliteInventoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies guarded NVIDIA MIG creation and destruction with hardware id tracking. */
class PartitionControlServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsAndDestroysHardwareBackedMigPartitionRecords() {
        String previous = System.getenv("GPUM_ENABLE_HARDWARE_WRITE");
        try {
            System.setProperty("gpum.enableHardwareWrite", "true");
            SystemInfoService systemInfoService = new SystemInfoService();
            String localHost = systemInfoService.localNodeInventory().hostname();

            Path dbPath = tempDir.resolve("mig.db");
            SqliteGovernanceRepository governanceRepository = new SqliteGovernanceRepository(dbPath);
            SqliteInventoryRepository inventoryRepository = new SqliteInventoryRepository(dbPath);
            SqliteAllocationRepository allocationRepository = new SqliteAllocationRepository(dbPath);
            inventoryRepository.initialize();
            inventoryRepository.saveNode(new NodeInventory(localHost, "Linux", "amd64", 64, 262_144, Instant.now()));
            inventoryRepository.replaceNodeGpus(localHost, List.of(new GpuDevice(
                    localHost,
                    GpuVendor.NVIDIA,
                    "0",
                    "NVIDIA H100",
                    "GPU-H100-0",
                    "0000:17:00.0",
                    "550.54.15",
                    81_480L,
                    80_000L,
                    0.0,
                    0.0,
                    30.0,
                    200.0,
                    700.0,
                    true,
                    InterconnectType.NVLINK,
                    HealthState.OK,
                    false,
                    false,
                    null,
                    true,
                    true,
                    true,
                    true,
                    Instant.now()
            )));

            List<List<String>> executed = new ArrayList<>();
            CommandExecutor executor = command -> {
                executed.add(List.copyOf(command));
                if (command.equals(List.of("nvidia-smi", "--query-compute-apps=pid,gpu_uuid,process_name", "--format=csv,noheader,nounits"))) {
                    return new CommandResult(command, 0, "", "");
                }
                if (command.equals(List.of("nvidia-smi", "mig", "-lgi", "-i", "GPU-H100-0"))) {
                    long count = executed.stream().filter(cmd -> cmd.equals(command)).count();
                    String stdout = count == 1 ? "No GPU instances found\n" : "GPU instance ID 2 on GPU 0\n";
                    return new CommandResult(command, 0, stdout, "");
                }
                if (command.equals(List.of("nvidia-smi", "mig", "-cgi", "1g.10gb", "-C", "-i", "GPU-H100-0"))) {
                    return new CommandResult(command, 0,
                            "Successfully created GPU instance ID 2 on GPU 0 using profile MIG 1g.10gb\n"
                                    + "Successfully created compute instance ID 0 on GPU 0 GPU instance ID 2 using profile MIG 1g.10gb\n",
                            "");
                }
                if (command.equals(List.of("nvidia-smi", "mig", "-lci", "-gi", "2", "-i", "GPU-H100-0"))) {
                    return new CommandResult(command, 0, "Compute instance ID 0 on GPU 0 GPU instance ID 2\n", "");
                }
                if (command.equals(List.of("nvidia-smi", "mig", "-dci", "-ci", "0", "-gi", "2", "-i", "GPU-H100-0"))) {
                    return new CommandResult(command, 0, "Successfully destroyed compute instance ID 0\n", "");
                }
                if (command.equals(List.of("nvidia-smi", "mig", "-dgi", "-gi", "2", "-i", "GPU-H100-0"))) {
                    return new CommandResult(command, 0, "Successfully destroyed GPU instance ID 2\n", "");
                }
                throw new IllegalStateException("Unexpected command: " + String.join(" ", command));
            };

            PartitionControlService service = new PartitionControlService(
                    governanceRepository,
                    inventoryRepository,
                    allocationRepository,
                    executor,
                    systemInfoService,
                    new GpuProcessService(executor, new GpumConfig(), systemInfoService),
                    new GpumConfig()
            );

            GpuDevice gpu = inventoryRepository.listGpus().getFirst();
            List<GpuPartitionRecord> created = service.createNvidiaMigPartitions(gpu, "1g.10gb", 1, false, "approval-1");
            assertEquals(1, created.size());
            assertTrue(created.getFirst().hardwareApplied());
            assertEquals("2", created.getFirst().hardwareGpuInstanceId());

            int removed = service.destroyPartitions(created, false);
            assertEquals(1, removed);
        } finally {
            if (previous == null) {
                System.clearProperty("gpum.enableHardwareWrite");
            } else {
                System.setProperty("gpum.enableHardwareWrite", previous);
            }
        }
    }
}
