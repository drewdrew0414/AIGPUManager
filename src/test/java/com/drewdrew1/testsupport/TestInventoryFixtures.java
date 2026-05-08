package com.drewdrew1.testsupport;

import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.core.model.HealthState;
import com.drewdrew1.core.model.InterconnectType;
import com.drewdrew1.core.model.NodeInventory;
import com.drewdrew1.infra.persistence.SqliteInventoryRepository;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/** Seeds a representative mixed-vendor GPU fleet for integration and scheduling tests. */
public final class TestInventoryFixtures {
    public static final Instant FIXTURE_TIME = Instant.parse("2026-05-07T00:00:00Z");

    private TestInventoryFixtures() {
    }

    public static void seedMixedFleet(SqliteInventoryRepository repository) {
        repository.initialize();

        repository.saveNode(new NodeInventory("nvidia-h-pool", "Linux", "amd64", 128, 1_048_576, FIXTURE_TIME));
        repository.saveNode(new NodeInventory("nvidia-rtx-pool", "Linux", "amd64", 64, 524_288, FIXTURE_TIME));
        repository.saveNode(new NodeInventory("amd-mi-pool", "Linux", "amd64", 128, 1_048_576, FIXTURE_TIME));
        repository.saveNode(new NodeInventory("intel-xe-pool", "Linux", "amd64", 96, 786_432, FIXTURE_TIME));

        repository.replaceNodeGpus("nvidia-h-pool", List.of(
                gpu("nvidia-h-pool", GpuVendor.NVIDIA, "0", "NVIDIA H100 80GB HBM3", "GPU-H100-0", "0000:17:00.0",
                        81_480L, 79_000L, 32.0, 45.0, 220.0, 700.0, true, InterconnectType.NVLINK, true),
                gpu("nvidia-h-pool", GpuVendor.NVIDIA, "1", "NVIDIA H200 141GB HBM3e", "GPU-H200-0", "0000:18:00.0",
                        141_312L, 140_000L, 21.0, 41.0, 240.0, 700.0, true, InterconnectType.NVLINK, true),
                gpu("nvidia-h-pool", GpuVendor.NVIDIA, "2", "NVIDIA B200", "GPU-B200-0", "0000:19:00.0",
                        183_296L, 182_000L, 18.0, 39.0, 260.0, 1000.0, true, InterconnectType.NVLINK, true),
                gpu("nvidia-h-pool", GpuVendor.NVIDIA, "3", "NVIDIA A100 80GB PCIe", "GPU-A100-0", "0000:1A:00.0",
                        81_480L, 78_000L, 14.0, 44.0, 210.0, 400.0, true, InterconnectType.NVLINK, true)
        ));

        repository.replaceNodeGpus("nvidia-rtx-pool", List.of(
                gpu("nvidia-rtx-pool", GpuVendor.NVIDIA, "0", "NVIDIA RTX 4090", "GPU-RTX4090-0", "0000:65:00.0",
                        24_564L, 22_000L, 9.0, 48.0, 280.0, 450.0, false, InterconnectType.PCIE, false),
                gpu("nvidia-rtx-pool", GpuVendor.NVIDIA, "1", "NVIDIA RTX 6000 Ada Generation", "GPU-RTX6000ADA-0", "0000:66:00.0",
                        49_140L, 47_000L, 7.0, 43.0, 250.0, 300.0, false, InterconnectType.PCIE, false),
                gpu("nvidia-rtx-pool", GpuVendor.NVIDIA, "2", "NVIDIA L40S", "GPU-L40S-0", "0000:67:00.0",
                        46_068L, 45_000L, 11.0, 46.0, 275.0, 350.0, false, InterconnectType.PCIE, false)
        ));

        repository.replaceNodeGpus("amd-mi-pool", List.of(
                gpu("amd-mi-pool", GpuVendor.AMD, "0", "AMD Instinct MI210", "GPU-MI210-0", "0000:81:00.0",
                        65_536L, 62_000L, 27.0, 47.0, 250.0, 300.0, true, InterconnectType.XGMI, false),
                gpu("amd-mi-pool", GpuVendor.AMD, "1", "AMD Instinct MI250X", "GPU-MI250X-0", "0000:82:00.0",
                        131_072L, 125_000L, 19.0, 44.0, 410.0, 560.0, true, InterconnectType.XGMI, false),
                gpu("amd-mi-pool", GpuVendor.AMD, "2", "AMD Instinct MI300X", "GPU-MI300X-0", "0000:83:00.0",
                        196_608L, 194_000L, 23.0, 40.0, 520.0, 750.0, true, InterconnectType.XGMI, false),
                gpu("amd-mi-pool", GpuVendor.AMD, "3", "AMD Radeon PRO W7900", "GPU-W7900-0", "0000:84:00.0",
                        49_152L, 46_000L, 6.0, 42.0, 190.0, 295.0, false, InterconnectType.PCIE, false)
        ));

        repository.replaceNodeGpus("intel-xe-pool", List.of(
                gpu("intel-xe-pool", GpuVendor.INTEL, "0", "Intel Data Center GPU Max 1100", "GPU-MAX1100-0", "0000:91:00.0",
                        49_152L, 47_000L, 16.0, 39.0, 240.0, 0.0, true, InterconnectType.XE_LINK, false),
                gpu("intel-xe-pool", GpuVendor.INTEL, "1", "Intel Data Center GPU Max 1550", "GPU-MAX1550-0", "0000:92:00.0",
                        65_536L, 63_000L, 15.0, 40.0, 275.0, 0.0, true, InterconnectType.XE_LINK, false),
                gpu("intel-xe-pool", GpuVendor.INTEL, "2", "Intel Arc Pro A60", "GPU-ARCPROA60-0", "0000:93:00.0",
                        12_288L, 10_000L, 5.0, 45.0, 130.0, 0.0, false, InterconnectType.PCIE, false),
                gpu("intel-xe-pool", GpuVendor.INTEL, "3", "Intel Data Center GPU Flex 170", "GPU-FLEX170-0", "0000:94:00.0",
                        16_384L, 15_000L, 8.0, 41.0, 150.0, 0.0, false, InterconnectType.XE_LINK, false)
        ));

        repository.putNodeAttribute("nvidia-h-pool", "label.role", "trainer");
        repository.putNodeAttribute("nvidia-h-pool", "label.vendor", "nvidia");
        repository.putNodeAttribute("nvidia-rtx-pool", "label.role", "viz");
        repository.putNodeAttribute("nvidia-rtx-pool", "label.vendor", "nvidia");
        repository.putNodeAttribute("amd-mi-pool", "label.role", "trainer");
        repository.putNodeAttribute("amd-mi-pool", "label.vendor", "amd");
        repository.putNodeAttribute("intel-xe-pool", "label.role", "inference");
        repository.putNodeAttribute("intel-xe-pool", "label.vendor", "intel");
    }

    public static Stream<ModelExpectation> representativeModels() {
        return Stream.of(
                new ModelExpectation(GpuVendor.NVIDIA, "NVIDIA H100 80GB HBM3", 80_000L, "cuda"),
                new ModelExpectation(GpuVendor.NVIDIA, "NVIDIA H200 141GB HBM3e", 140_000L, "mig"),
                new ModelExpectation(GpuVendor.NVIDIA, "NVIDIA B200", 180_000L, "nvlink"),
                new ModelExpectation(GpuVendor.NVIDIA, "NVIDIA A100 80GB PCIe", 80_000L, "mig"),
                new ModelExpectation(GpuVendor.NVIDIA, "NVIDIA RTX 4090", 24_000L, "cuda"),
                new ModelExpectation(GpuVendor.NVIDIA, "NVIDIA RTX 6000 Ada Generation", 48_000L, "cuda"),
                new ModelExpectation(GpuVendor.NVIDIA, "NVIDIA L40S", 45_000L, "cuda"),
                new ModelExpectation(GpuVendor.AMD, "AMD Instinct MI210", 64_000L, "rocm"),
                new ModelExpectation(GpuVendor.AMD, "AMD Instinct MI250X", 128_000L, "xgmi"),
                new ModelExpectation(GpuVendor.AMD, "AMD Instinct MI300X", 190_000L, "rocm"),
                new ModelExpectation(GpuVendor.AMD, "AMD Radeon PRO W7900", 48_000L, "rocm"),
                new ModelExpectation(GpuVendor.INTEL, "Intel Data Center GPU Max 1100", 48_000L, "oneapi"),
                new ModelExpectation(GpuVendor.INTEL, "Intel Data Center GPU Max 1550", 64_000L, "xe-link"),
                new ModelExpectation(GpuVendor.INTEL, "Intel Arc Pro A60", 12_000L, "oneapi"),
                new ModelExpectation(GpuVendor.INTEL, "Intel Data Center GPU Flex 170", 16_000L, "xe-link")
        );
    }

    private static GpuDevice gpu(
            String node,
            GpuVendor vendor,
            String deviceId,
            String model,
            String uuid,
            String pciBusId,
            long totalMb,
            long freeMb,
            double gpuUtil,
            double temperatureC,
            double powerUsageW,
            double powerLimitW,
            boolean eccEnabled,
            InterconnectType interconnectType,
            boolean supportsMig
    ) {
        return new GpuDevice(
                node,
                vendor,
                deviceId,
                model,
                uuid,
                pciBusId,
                switch (vendor) {
                    case NVIDIA -> "550.54.15";
                    case AMD -> "6.2.0";
                    case INTEL -> "1.5.0";
                },
                totalMb,
                freeMb,
                gpuUtil,
                Math.min(gpuUtil / 2.0, 100.0),
                temperatureC,
                powerUsageW,
                powerLimitW == 0.0 ? null : powerLimitW,
                eccEnabled,
                interconnectType,
                HealthState.OK,
                false,
                false,
                null,
                supportsMig,
                supportsMig,
                true,
                true,
                FIXTURE_TIME
        );
    }

    /** Describes one representative GPU model family that should remain schedulable. */
    public record ModelExpectation(
            GpuVendor vendor,
            String model,
            long minVramMb,
            String capability
    ) {
    }
}
