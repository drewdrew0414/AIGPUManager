package com.drewdrew1.core.service;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.core.model.AllocationAffinity;
import com.drewdrew1.core.model.AllocationDecision;
import com.drewdrew1.core.model.AllocationRequest;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.infra.persistence.SqliteAllocationRepository;
import com.drewdrew1.infra.persistence.SqliteInventoryRepository;
import com.drewdrew1.testsupport.TestInventoryFixtures;
import com.drewdrew1.testsupport.TestInventoryFixtures.ModelExpectation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that representative NVIDIA, AMD, and Intel GPU families remain allocatable. */
class GpuFleetCoverageTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @MethodSource("com.drewdrew1.testsupport.TestInventoryFixtures#representativeModels")
    void dryRunFindsCandidateForEachRepresentativeModel(ModelExpectation modelExpectation) {
        Path dbPath = tempDir.resolve("coverage-" + sanitize(modelExpectation.model()) + ".db");
        SqliteInventoryRepository inventoryRepository = new SqliteInventoryRepository(dbPath);
        SqliteAllocationRepository allocationRepository = new SqliteAllocationRepository(dbPath);
        TestInventoryFixtures.seedMixedFleet(inventoryRepository);

        AllocationService service = new AllocationService(inventoryRepository, allocationRepository);
        AllocationDecision decision = service.dryRun(new AllocationRequest(
                "tester",
                null,
                1,
                modelExpectation.model(),
                modelExpectation.minVramMb(),
                false,
                4,
                5,
                false,
                AllocationAffinity.PACKED,
                null
        ));

        assertEquals(1, decision.devices().size());
        assertEquals(modelExpectation.vendor(), decision.devices().getFirst().vendor());
        assertTrue(decision.devices().getFirst().model().contains(modelExpectation.model()));
    }

    @Test
    void fixtureFleetExposesExpectedCapabilitiesAcrossVendors() {
        Path dbPath = tempDir.resolve("capabilities.db");
        SqliteInventoryRepository inventoryRepository = new SqliteInventoryRepository(dbPath);
        TestInventoryFixtures.seedMixedFleet(inventoryRepository);

        List<GpuDevice> gpus = inventoryRepository.listGpus();
        for (ModelExpectation modelExpectation : TestInventoryFixtures.representativeModels().toList()) {
            GpuDevice gpu = gpus.stream()
                    .filter(candidate -> candidate.vendor() == modelExpectation.vendor())
                    .filter(candidate -> candidate.model().equals(modelExpectation.model()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(CliSupport.matchesCapability(gpu, modelExpectation.capability()),
                    () -> "Expected capability " + modelExpectation.capability() + " for " + modelExpectation.model());
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9]+", "-");
    }
}
