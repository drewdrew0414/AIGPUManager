package com.drewdrew1.core.service;

import com.drewdrew1.core.config.GpumConfig;
import com.drewdrew1.core.model.AllocationAffinity;
import com.drewdrew1.core.model.AllocationDevice;
import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.AllocationStatus;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.testsupport.RecordingCommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies AI launch templating and allocation-aware Kubernetes manifest patching. */
class IntegrationServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsAiArgumentTemplateWithAllocationVariables() throws Exception {
        IntegrationService service = new IntegrationService(new RecordingCommandExecutor(), new GpumConfig());
        AllocationRecord allocation = allocation();
        Path template = tempDir.resolve("torchrun.args");
        Files.writeString(template, """
                # launch args
                --nproc-per-node
                {{GPUM_GPU_COUNT}}
                train.py
                --allocation
                {{GPUM_ALLOCATION_ID}}
                """);

        List<String> args = service.loadArgumentTemplate(template, service.allocationEnvironment(allocation));

        assertEquals(List.of("--nproc-per-node", "2", "train.py", "--allocation", "alloc-1"), args);
    }

    @Test
    void patchesKubernetesManifestWithAllocationEnvironmentAndGpuResources() {
        RecordingCommandExecutor executor = new RecordingCommandExecutor();
        GpumConfig config = new GpumConfig();
        config.getKubernetes().setNamespace("ml");

        IntegrationService service = new IntegrationService(executor, config);
        IntegrationService.KubernetesSubmitPlan plan = service.kubernetesSubmitPlan(new IntegrationService.KubernetesSubmitRequest(
                "trainer",
                "repo/train:latest",
                null,
                "ml",
                "Job",
                null,
                null,
                allocation(),
                List.of(),
                List.of(),
                null,
                null,
                List.of(),
                0,
                0,
                false,
                false
        ));

        String manifest = plan.manifestYaml();
        assertTrue(manifest.contains("gpum_allocation"));
        assertTrue(manifest.contains("alloc-1"));
        assertTrue(manifest.contains("kubernetes.io/hostname"));
        assertTrue(manifest.contains("node-a"));
        assertTrue(manifest.contains("CUDA_VISIBLE_DEVICES"));
        assertTrue(manifest.contains("nvidia.com/gpu"));
        assertTrue(manifest.contains(" 2") || manifest.contains(": 2") || manifest.contains("\"2\""));
    }

    @Test
    void rendersTorchrunPresetWithRendezvousHints() {
        IntegrationService service = new IntegrationService(new RecordingCommandExecutor(), new GpumConfig());
        IntegrationService.AiPresetPlan plan = service.aiPresetPlan("torchrun-ddp", allocation(), "train.py", List.of("--epochs", "1"));

        assertEquals("torchrun", plan.tool());
        assertTrue(plan.args().contains("--rdzv-endpoint"));
        assertTrue(plan.args().contains("train.py"));
        assertTrue(plan.args().contains("--epochs"));
    }

    private AllocationRecord allocation() {
        Instant now = Instant.parse("2026-05-08T00:00:00Z");
        return new AllocationRecord(
                "alloc-1",
                "tester",
                null,
                AllocationStatus.ACTIVE,
                false,
                5,
                false,
                AllocationAffinity.PACKED,
                2,
                "H100",
                80000L,
                null,
                "node-a",
                now,
                now.plusSeconds(3600),
                null,
                List.of(
                        new AllocationDevice("node-a", GpuVendor.NVIDIA, "0", "GPU-0", "NVIDIA H100 80GB HBM3", "0000:17:00.0"),
                        new AllocationDevice("node-a", GpuVendor.NVIDIA, "1", "GPU-1", "NVIDIA H100 80GB HBM3", "0000:18:00.0")
                )
        );
    }
}
