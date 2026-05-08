package com.drewdrew1.core.service;

import com.drewdrew1.core.config.GpumConfig;
import com.drewdrew1.infra.persistence.SqliteAllocationRepository;
import com.drewdrew1.testsupport.RecordingCommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests Docker and Kubernetes reconcile parsing without requiring either runtime to be installed. */
class ContainerReconcileServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsVisibleDockerContainersAndEmptyGpumAllocations() {
        GpumConfig config = new GpumConfig();
        RecordingCommandExecutor executor = new RecordingCommandExecutor()
                .addSuccess(List.of("docker", "ps", "--format", "{{.ID}} {{.Names}}"), "abc trainer\n");
        ContainerReconcileService service = new ContainerReconcileService(
                executor,
                new SqliteAllocationRepository(tempDir.resolve("docker.db")),
                config
        );

        List<ContainerReconcileService.ReconcileFinding> findings = service.reconcileDocker();

        assertTrue(findings.stream().anyMatch(item -> item.type().equals("container-visible")));
        assertTrue(findings.stream().anyMatch(item -> item.type().equals("no-active-allocations")));
    }

    @Test
    void reportsUntrackedKubernetesPods() {
        GpumConfig config = new GpumConfig();
        String pods = """
                {"items":[{"metadata":{"namespace":"ml","name":"trainer","labels":{}}}]}
                """;
        RecordingCommandExecutor executor = new RecordingCommandExecutor()
                .addSuccess(List.of("kubectl", "get", "pods", "-A", "-o", "json"), pods);
        ContainerReconcileService service = new ContainerReconcileService(
                executor,
                new SqliteAllocationRepository(tempDir.resolve("k8s.db")),
                config
        );

        List<ContainerReconcileService.ReconcileFinding> findings = service.reconcileKubernetes();

        assertEquals(1, findings.size());
        assertEquals("untracked-pod", findings.getFirst().type());
    }
}
