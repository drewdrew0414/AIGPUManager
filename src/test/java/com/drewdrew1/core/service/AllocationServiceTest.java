package com.drewdrew1.core.service;

import com.drewdrew1.core.model.AllocationAffinity;
import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.AllocationRequest;
import com.drewdrew1.core.model.AllocationStatus;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.core.model.HealthState;
import com.drewdrew1.core.model.InterconnectType;
import com.drewdrew1.core.model.NodeInventory;
import com.drewdrew1.infra.persistence.SqliteAllocationRepository;
import com.drewdrew1.infra.persistence.SqliteInventoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies allocation scheduling, claim handling, and expiration behavior. */
class AllocationServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void allocatesPackedGpusOnSingleLabeledNode() {
        Path dbPath = tempDir.resolve("alloc.db");
        SqliteInventoryRepository inventoryRepository = new SqliteInventoryRepository(dbPath);
        SqliteAllocationRepository allocationRepository = new SqliteAllocationRepository(dbPath);
        seedInventory(inventoryRepository);
        inventoryRepository.putNodeAttribute("node-a", "label.role", "trainer");
        inventoryRepository.putNodeAttribute("node-b", "label.role", "trainer");

        AllocationService service = new AllocationService(inventoryRepository, allocationRepository);
        AllocationRecord record = service.allocate(new AllocationRequest(
                "tester", null, 2, "H100", 80000L, false, 4, 5, false, AllocationAffinity.PACKED, "role=trainer"
        ));

        assertEquals(AllocationStatus.ACTIVE, record.status());
        assertEquals(2, record.devices().size());
        assertTrue(record.devices().stream().allMatch(device -> "node-a".equals(device.nodeHostname())));
    }

    @Test
    void preventsDuplicateAllocationUntilReleased() {
        Path dbPath = tempDir.resolve("alloc-dup.db");
        SqliteInventoryRepository inventoryRepository = new SqliteInventoryRepository(dbPath);
        SqliteAllocationRepository allocationRepository = new SqliteAllocationRepository(dbPath);
        seedInventory(inventoryRepository);
        inventoryRepository.putNodeAttribute("node-a", "label.pool", "solo");

        AllocationService service = new AllocationService(inventoryRepository, allocationRepository);
        AllocationRecord first = service.allocate(new AllocationRequest(
                "tester", null, 2, "H100", 80000L, false, 4, 5, false, AllocationAffinity.PACKED, "pool=solo"
        ));

        assertThrows(IllegalStateException.class, () -> service.allocate(new AllocationRequest(
                "tester", null, 2, "H100", 80000L, false, 4, 5, false, AllocationAffinity.PACKED, "pool=solo"
        )));

        AllocationRecord released = service.releaseAllocation(first.id());
        assertEquals(AllocationStatus.RELEASED, released.status());

        AllocationRecord second = service.allocate(new AllocationRequest(
                "tester", null, 2, "H100", 80000L, false, 4, 5, false, AllocationAffinity.PACKED, "pool=solo"
        ));
        assertEquals(2, second.devices().size());
    }

    @Test
    void reapsExpiredAllocationsAndFreesGpuClaims() {
        Path dbPath = tempDir.resolve("alloc-expire.db");
        SqliteInventoryRepository inventoryRepository = new SqliteInventoryRepository(dbPath);
        SqliteAllocationRepository allocationRepository = new SqliteAllocationRepository(dbPath);
        seedInventory(inventoryRepository);
        inventoryRepository.putNodeAttribute("node-a", "label.pool", "ttl");

        AllocationService service = new AllocationService(inventoryRepository, allocationRepository);
        AllocationRecord record = service.allocate(new AllocationRequest(
                "tester", null, 1, "H100", 80000L, false, 1, 5, false, AllocationAffinity.PACKED, "pool=ttl"
        ));

        allocationRepository.updateExpiresAt(record.id(), Instant.now().minusSeconds(60));
        int expired = service.reapExpiredAllocations();
        assertEquals(1, expired);
        assertEquals(AllocationStatus.EXPIRED, service.findAllocation(record.id()).orElseThrow().status());

        AllocationRecord second = service.allocate(new AllocationRequest(
                "tester", null, 1, "H100", 80000L, false, 1, 5, false, AllocationAffinity.PACKED, "pool=ttl"
        ));
        assertEquals(1, second.devices().size());
    }

    @Test
    void treatsIntegratedIntelSharedMemoryAsAllocatableCapacity() {
        Path dbPath = tempDir.resolve("alloc-intel-igpu.db");
        SqliteInventoryRepository inventoryRepository = new SqliteInventoryRepository(dbPath);
        SqliteAllocationRepository allocationRepository = new SqliteAllocationRepository(dbPath);
        Instant now = Instant.parse("2026-05-07T00:00:00Z");

        inventoryRepository.saveNode(new NodeInventory("intel-igpu", "Windows 11", "amd64", 16, 32768, now));
        inventoryRepository.replaceNodeGpus("intel-igpu", List.of(new GpuDevice(
                "intel-igpu",
                GpuVendor.INTEL,
                "0",
                "Intel Arc 140V GPU",
                "INTEL-140V-0",
                null,
                "32.0.101.6734",
                256L,
                null,
                18.0,
                null,
                null,
                null,
                null,
                null,
                InterconnectType.PCIE,
                HealthState.OK,
                true,
                true,
                14336L,
                false,
                false,
                true,
                true,
                now
        )));
        inventoryRepository.putNodeAttribute("intel-igpu", "label.role", "edge");

        AllocationService service = new AllocationService(inventoryRepository, allocationRepository);
        AllocationRecord record = service.allocate(new AllocationRequest(
                "tester", null, 1, "140V", 8192L, false, 2, 5, false, AllocationAffinity.PACKED, "role=edge"
        ));

        assertEquals(1, record.devices().size());
        assertEquals("intel-igpu", record.devices().getFirst().nodeHostname());
    }

    private void seedInventory(SqliteInventoryRepository inventoryRepository) {
        Instant now = Instant.parse("2026-05-07T00:00:00Z");
        inventoryRepository.saveNode(new NodeInventory("node-a", "Linux", "amd64", 64, 524288, now));
        inventoryRepository.saveNode(new NodeInventory("node-b", "Linux", "amd64", 64, 524288, now));
        inventoryRepository.replaceNodeGpus("node-a", List.of(
                gpu("node-a", "0", "GPU-A0", "NVIDIA H100 80GB HBM3", "0000:17:00.0", now),
                gpu("node-a", "1", "GPU-A1", "NVIDIA H100 80GB HBM3", "0000:18:00.0", now)
        ));
        inventoryRepository.replaceNodeGpus("node-b", List.of(
                gpu("node-b", "0", "GPU-B0", "NVIDIA H100 80GB HBM3", "0000:65:00.0", now),
                gpu("node-b", "1", "GPU-B1", "NVIDIA H100 80GB HBM3", "0000:66:00.0", now)
        ));
    }

    private GpuDevice gpu(String node, String deviceId, String uuid, String model, String pci, Instant now) {
        return new GpuDevice(
                node,
                GpuVendor.NVIDIA,
                deviceId,
                model,
                uuid,
                pci,
                "550.54.15",
                81480L,
                80000L,
                5.0,
                1.0,
                41.0,
                120.0,
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
                now
        );
    }
}
