package com.drewdrew1.core.service;

import com.drewdrew1.core.config.GpumConfig;
import com.drewdrew1.core.repository.AllocationRepository;
import com.drewdrew1.infra.executor.CommandExecutionException;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Compares container orchestrator visibility with gpum allocation state. */
public class ContainerReconcileService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CommandExecutor commandExecutor;
    private final AllocationRepository allocationRepository;
    private final GpumConfig config;

    public ContainerReconcileService(CommandExecutor commandExecutor, AllocationRepository allocationRepository, GpumConfig config) {
        this.commandExecutor = commandExecutor;
        this.allocationRepository = allocationRepository;
        this.config = config;
    }

    public List<ReconcileFinding> reconcileDocker() {
        List<ReconcileFinding> findings = new ArrayList<>();
        try {
            CommandResult result = commandExecutor.execute(List.of(
                    config.getTools().getDocker(),
                    "ps",
                    "--format",
                    "{{.ID}} {{.Names}}"
            ));
            if (!result.isSuccess()) {
                return List.of(new ReconcileFinding("docker", "unavailable", "docker ps failed"));
            }
            for (String line : result.stdout().split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                findings.add(new ReconcileFinding("docker", "container-visible", line.trim()));
            }
        } catch (CommandExecutionException e) {
            findings.add(new ReconcileFinding("docker", "unavailable", e.getMessage()));
        }
        if (allocationRepository.listActive().isEmpty()) {
            findings.add(new ReconcileFinding("gpum", "no-active-allocations", "no active allocation claims to compare"));
        }
        return findings;
    }

    public List<ReconcileFinding> reconcileKubernetes() {
        List<ReconcileFinding> findings = new ArrayList<>();
        try {
            CommandResult result = commandExecutor.execute(List.of(
                    config.getTools().getKubectl(),
                    "get",
                    "pods",
                    "-A",
                    "-o",
                    "json"
            ));
            if (!result.isSuccess()) {
                return List.of(new ReconcileFinding("k8s", "unavailable", "kubectl get pods failed"));
            }
            JsonNode items = OBJECT_MAPPER.readTree(result.stdout()).path("items");
            if (items.isArray()) {
                for (JsonNode pod : items) {
                    String namespace = pod.path("metadata").path("namespace").asText("-");
                    String name = pod.path("metadata").path("name").asText("-");
                    String allocation = pod.path("metadata").path("labels").path("gpum_allocation").asText("");
                    if (allocation.isBlank()) {
                        findings.add(new ReconcileFinding("k8s", "untracked-pod", namespace + "/" + name));
                    } else if (allocationRepository.findById(allocation).isEmpty()) {
                        findings.add(new ReconcileFinding("k8s", "stale-allocation-label", namespace + "/" + name + " allocation=" + allocation));
                    }
                }
            }
        } catch (Exception e) {
            findings.add(new ReconcileFinding("k8s", "unavailable", e.getMessage()));
        }
        return findings;
    }

    /** Represents one container/orchestrator visibility finding. */
    public record ReconcileFinding(String source, String type, String detail) {
    }
}
