package com.drewdrew1.core.service;

import com.drewdrew1.core.detector.DetectionResult;
import com.drewdrew1.core.detector.GpuDetector;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.core.model.HealthState;
import com.drewdrew1.core.model.InterconnectType;
import com.drewdrew1.core.model.NodeInventory;
import com.drewdrew1.core.model.ScanSummary;
import com.drewdrew1.infra.persistence.SqliteInventoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void scanPersistsNodeAndGpuInventory() {
        Path dbPath = tempDir.resolve("gpu-mgr.db");
        SqliteInventoryRepository repository = new SqliteInventoryRepository(dbPath);
        Instant scannedAt = Instant.parse("2026-05-07T00:00:00Z");

        GpuDetector detector = (hostname, timestamp) -> new DetectionResult(
                GpuVendor.NVIDIA,
                List.of(new GpuDevice(
                        hostname,
                        GpuVendor.NVIDIA,
                        "0",
                        "NVIDIA H100",
                        "GPU-1",
                        "0000:01:00.0",
                        "550.54.15",
                        80000L,
                        76000L,
                        5.0,
                        2.0,
                        41.0,
                        120.0,
                        700.0,
                        true,
                        InterconnectType.NVLINK,
                        HealthState.OK,
                        true,
                        true,
                        true,
                        true,
                        scannedAt
                )),
                List.of()
        );

        InventoryService service = new InventoryService(
                repository,
                List.of(detector),
                new SystemInfoService() {
                    @Override
                    public NodeInventory localNodeInventory() {
                        return new NodeInventory("node-test", "Linux", "amd64", 64, 524288, scannedAt);
                    }
                }
        );

        ScanSummary summary = service.scanLocalNode();

        assertEquals(1, summary.discoveredGpuCount());
        assertEquals(1, repository.listNodes().size());
        assertEquals(1, repository.listGpus().size());
        assertEquals("node-test", repository.listGpus().get(0).nodeHostname());
    }
}
