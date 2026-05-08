package com.drewdrew1.core.service;

import com.drewdrew1.infra.persistence.SqliteAllocationRepository;
import com.drewdrew1.infra.persistence.SqliteRuntimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies runtime worker registry and safe migration planning without launching real workloads. */
class RuntimeWorkerServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void registersWorkersAndBuildsMigrationPlan() {
        RuntimeWorkerService service = new RuntimeWorkerService(
                new SqliteRuntimeRepository(tempDir.resolve("runtime.db")),
                new SqliteAllocationRepository(tempDir.resolve("runtime.db"))
        );

        var worker = service.register(
                "worker-a",
                null,
                "research",
                "alice",
                "echo train",
                null,
                Map.of("CUDA_VISIBLE_DEVICES", "0"),
                "echo checkpoint",
                "echo restore",
                "echo defrag",
                2,
                60,
                32768L
        );

        assertEquals("worker-a", worker.id());
        assertEquals("REGISTERED", worker.status());
        assertEquals(1, service.list().size());
        assertFalse(service.listEvents("worker-a", 10).isEmpty());

        RuntimeWorkerService.MigrationPlan plan = service.migrationPlan("worker-a", "node-b");
        assertTrue(plan.executable());
        assertEquals("node-b", plan.targetNode());
    }

    @Test
    void recyclePreviewDoesNotStartProcesses() {
        RuntimeWorkerService service = new RuntimeWorkerService(
                new SqliteRuntimeRepository(tempDir.resolve("runtime-preview.db")),
                new SqliteAllocationRepository(tempDir.resolve("runtime-preview.db"))
        );
        service.register("worker-b", null, null, "bob", "echo run", null, Map.of(), null, null, null, 1, 1, null);

        assertTrue(service.recycleDueWorkers(false).isEmpty());
    }
}
