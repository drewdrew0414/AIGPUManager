package com.drewdrew1.core.service;

import com.drewdrew1.core.config.GpumConfig;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.core.model.HealthState;
import com.drewdrew1.core.model.InterconnectType;
import com.drewdrew1.core.model.NodeInventory;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;
import com.drewdrew1.infra.persistence.SqliteInventoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies remote hardware writes are routed to the recorded SSH target and agent command. */
class RemoteGpuControlServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void routesRemoteGpuSetThroughConfiguredAgentCommand() {
        Path dbPath = tempDir.resolve("remote.db");
        SqliteInventoryRepository inventoryRepository = new SqliteInventoryRepository(dbPath);
        inventoryRepository.initialize();
        inventoryRepository.saveNode(new NodeInventory("remote-node", "Linux", "amd64", 64, 262_144, Instant.now()));
        inventoryRepository.replaceNodeGpus("remote-node", List.of(new GpuDevice(
                "remote-node",
                GpuVendor.NVIDIA,
                "0",
                "NVIDIA H100",
                "GPU-REMOTE-0",
                "0000:01:00.0",
                "550.54.15",
                81_480L,
                80_000L,
                10.0,
                5.0,
                35.0,
                220.0,
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
        inventoryRepository.putNodeAttribute("remote-node", "remote.address", "10.0.0.10");
        inventoryRepository.putNodeAttribute("remote-node", "remote.user", "gpuadmin");

        GpumConfig config = new GpumConfig();
        config.getTools().setGpumAgentCommand("/opt/gpum/bin/gpum");

        final List<String>[] captured = new List[1];
        RemoteGpuControlService service = new RemoteGpuControlService(
                inventoryRepository,
                config,
                (address, sshUser) -> command -> {
                    captured[0] = command;
                    return new CommandResult(command, 0, "ok", "");
                }
        );

        var gpu = inventoryRepository.listGpus().getFirst();
        var result = service.applySettings(
                gpu,
                new GpuControlService.SetRequest(true, false, false, 350, null, null, null),
                null
        );

        assertEquals("10.0.0.10", result.address());
        assertEquals("gpuadmin", result.sshUser());
        assertTrue(captured[0].contains("/opt/gpum/bin/gpum"));
        assertTrue(captured[0].contains("--power-limit"));
        assertTrue(captured[0].contains("350"));
    }
}
