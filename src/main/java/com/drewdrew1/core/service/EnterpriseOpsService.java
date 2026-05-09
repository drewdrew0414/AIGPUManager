package com.drewdrew1.core.service;

import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.AllocationStatus;
import com.drewdrew1.core.model.OpsRecord;
import com.drewdrew1.core.repository.AllocationRepository;
import com.drewdrew1.core.repository.OpsRepository;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Coordinates production-oriented policies that span compute, scheduling, data, jobs, and governance. */
public class EnterpriseOpsService {
    private final OpsRepository opsRepository;
    private final AllocationRepository allocationRepository;
    private final RuntimeWorkerService runtimeWorkerService;
    private final CommandExecutor commandExecutor;

    public EnterpriseOpsService(
            OpsRepository opsRepository,
            AllocationRepository allocationRepository,
            RuntimeWorkerService runtimeWorkerService,
            CommandExecutor commandExecutor
    ) {
        this.opsRepository = opsRepository;
        this.allocationRepository = allocationRepository;
        this.runtimeWorkerService = runtimeWorkerService;
        this.commandExecutor = commandExecutor;
    }

    public OpsRecord record(String domain, String type, String name, String owner, String target, String status, Map<String, String> metadata) {
        opsRepository.initialize();
        Instant now = Instant.now();
        return opsRepository.upsert(new OpsRecord(
                type + "-" + UUID.randomUUID(),
                domain,
                type,
                blankDefault(name, type),
                blankDefault(owner, "unknown"),
                blankToNull(target),
                status,
                metadata == null ? Map.of() : new LinkedHashMap<>(metadata),
                now,
                now
        ));
    }

    public List<OpsRecord> list(String domain, String type) {
        return opsRepository.list(domain, type);
    }

    public Optional<OpsRecord> find(String id) {
        return opsRepository.find(id);
    }

    public int cancel(String id) {
        return opsRepository.updateStatus(id, "CANCELLED");
    }

    public OpsPlan quotaPlan(String allocationId, String name, Integer cpuCores, Long memoryMb, Integer pids, Long pid, boolean execute) {
        requireActiveAllocation(allocationId, "allocation-id");
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("allocationId", allocationId);
        metadata.put("cpuCores", string(cpuCores));
        metadata.put("memoryMb", string(memoryMb));
        metadata.put("pids", string(pids));
        metadata.put("pid", string(pid));
        metadata.put("execute", Boolean.toString(execute));

        List<List<String>> commands = new ArrayList<>();
        String group = "gpum-" + allocationId.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (isWindows()) {
            commands.add(List.of("powershell", "-NoProfile", "-Command",
                    "Write-Output 'Use Windows Job Objects or container limits for " + group + "'"));
        } else {
            commands.add(List.of("cgcreate", "-g", "cpu,memory,pids:/" + group));
            if (cpuCores != null) {
                commands.add(List.of("cgset", "-r", "cpu.max=" + (cpuCores * 100000) + " 100000", group));
            }
            if (memoryMb != null) {
                commands.add(List.of("cgset", "-r", "memory.max=" + (memoryMb * 1024L * 1024L), group));
            }
            if (pids != null) {
                commands.add(List.of("cgset", "-r", "pids.max=" + pids, group));
            }
            if (pid != null) {
                commands.add(List.of("cgclassify", "-g", "cpu,memory,pids:/" + group, Long.toString(pid)));
            }
        }
        OpsRecord record = record("compute", "quota", name, currentUser(), allocationId, execute ? "APPLIED" : "PLANNED", metadata);
        List<CommandResult> results = execute ? executeAll(commands) : List.of();
        return new OpsPlan(record, commands, results, warningsForMissingExecute(execute));
    }

    public OpsPlan rdmaPolicy(String name, String node, String device, Integer bandwidthMbit, Integer priority, boolean execute) {
        Map<String, String> metadata = mapOf(
                "node", node,
                "device", device,
                "bandwidthMbit", string(bandwidthMbit),
                "priority", string(priority),
                "execute", Boolean.toString(execute)
        );
        List<List<String>> commands = List.of(
                List.of("tc", "qdisc", "replace", "dev", device, "root", "prio"),
                List.of("tc", "class", "replace", "dev", device, "parent", "1:", "classid", "1:1", "htb", "rate", bandwidthMbit + "mbit", "prio", priority.toString())
        );
        OpsRecord record = record("compute", "rdma", name, currentUser(), node + ":" + device, execute ? "APPLIED" : "PLANNED", metadata);
        return new OpsPlan(record, commands, execute ? executeAll(commands) : List.of(), warningsForMissingExecute(execute));
    }

    public OpsRecord accelerator(String name, String kind, String driver, String endpoint, String labels) {
        return record("compute", "accelerator", name, currentUser(), endpoint, "REGISTERED",
                mapOf("kind", kind, "driver", driver, "labels", labels));
    }

    public OpsRecord modelQuota(String name, String tenant, String gpuModel, int maxGpus) {
        return record("compute", "model-quota", name, currentUser(), tenant, "ACTIVE",
                mapOf("tenant", tenant, "gpuModel", gpuModel, "maxGpus", Integer.toString(maxGpus)));
    }

    public OpsRecord reservation(String name, String queue, Instant start, Instant end, int gpus, int nodes, String project) {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("reservation end must be after start");
        }
        return record("schedule", "reservation", name, currentUser(), queue, "RESERVED", mapOf(
                "start", start.toString(),
                "end", end.toString(),
                "gpus", Integer.toString(gpus),
                "nodes", Integer.toString(nodes),
                "project", project
        ));
    }

    public OpsRecord queue(String name, String tenant, int weight, int maxGpus, boolean preemptible) {
        return record("schedule", "queue", name, currentUser(), tenant, "ACTIVE", mapOf(
                "tenant", tenant,
                "weight", Integer.toString(weight),
                "maxGpus", Integer.toString(maxGpus),
                "preemptible", Boolean.toString(preemptible)
        ));
    }

    public OpsPlan gangPlan(String name, int nodes, int gpusPerNode, String labelSelector, boolean reserve) {
        long matchingNodes = allocationRepository.listActive().stream()
                .map(AllocationRecord::primaryNodeHostname)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .count();
        Map<String, String> metadata = mapOf(
                "nodes", Integer.toString(nodes),
                "gpusPerNode", Integer.toString(gpusPerNode),
                "labelSelector", labelSelector,
                "activeNodes", Long.toString(matchingNodes),
                "reserve", Boolean.toString(reserve)
        );
        List<String> warnings = new ArrayList<>();
        if (matchingNodes < nodes) {
            warnings.add("not enough active allocation nodes are currently visible for gang start");
        }
        OpsRecord record = record("schedule", "gang", name, currentUser(), labelSelector, reserve ? "RESERVED" : "PLANNED", metadata);
        return new OpsPlan(record, List.of(), List.of(), warnings);
    }

    public FairShareReport fairShare(String owner, int windowHours) {
        Instant cutoff = Instant.now().minus(Duration.ofHours(windowHours));
        double gpuHours = 0.0;
        int allocations = 0;
        for (AllocationRecord record : allocationRepository.list()) {
            if (owner != null && !owner.equalsIgnoreCase(record.owner())) {
                continue;
            }
            Instant end = record.releasedAt() == null ? Instant.now() : record.releasedAt();
            if (end.isBefore(cutoff)) {
                continue;
            }
            Instant start = record.createdAt().isBefore(cutoff) ? cutoff : record.createdAt();
            double hours = Math.max(0.0, Duration.between(start, end).toMinutes() / 60.0);
            gpuHours += hours * Math.max(1, record.devices().size());
            allocations++;
        }
        double score = Math.max(1.0, 100.0 - Math.min(99.0, gpuHours));
        return new FairShareReport(owner == null ? "all" : owner, windowHours, allocations, gpuHours, score);
    }

    public OpsPlan preempt(String name, String victimAllocationId, String incoming, String suspendCommand, String resumeCommand, boolean execute) {
        requireActiveAllocation(victimAllocationId, "victim-allocation-id");
        Map<String, String> metadata = mapOf(
                "victimAllocationId", victimAllocationId,
                "incoming", incoming,
                "suspendCommand", suspendCommand,
                "resumeCommand", resumeCommand,
                "execute", Boolean.toString(execute)
        );
        List<List<String>> commands = new ArrayList<>();
        if (suspendCommand != null && !suspendCommand.isBlank()) {
            commands.add(shellCommand(suspendCommand));
        }
        if (resumeCommand != null && !resumeCommand.isBlank()) {
            commands.add(shellCommand(resumeCommand));
        }
        OpsRecord record = record("schedule", "preemption", name, currentUser(), victimAllocationId, execute ? "APPLIED" : "PLANNED", metadata);
        return new OpsPlan(record, commands, execute ? executeAll(commands) : List.of(), warningsForMissingExecute(execute));
    }

    public OpsRecord budgetAlert(String name, String owner, double budget, double ratePerGpuHour, int windowHours) {
        FairShareReport usage = fairShare(owner, windowHours);
        double projectedCost = usage.gpuHours() * ratePerGpuHour;
        String status = projectedCost >= budget ? "OVER_BUDGET" : "ACTIVE";
        return record("governance", "budget", name, currentUser(), owner, status, mapOf(
                "owner", owner,
                "budget", String.format(Locale.ROOT, "%.2f", budget),
                "ratePerGpuHour", String.format(Locale.ROOT, "%.2f", ratePerGpuHour),
                "windowHours", Integer.toString(windowHours),
                "gpuHours", String.format(Locale.ROOT, "%.2f", usage.gpuHours()),
                "projectedCost", String.format(Locale.ROOT, "%.2f", projectedCost)
        ));
    }

    public CostEstimate costEstimate(String owner, String gpuModel, int gpus, double hours, double ratePerGpuHour) {
        double cost = gpus * hours * ratePerGpuHour;
        return new CostEstimate(owner, gpuModel, gpus, hours, ratePerGpuHour, cost);
    }

    public OpsPlan dataCache(String name, String source, Path target, boolean execute) {
        Map<String, String> metadata = mapOf("source", source, "target", target.toString(), "execute", Boolean.toString(execute));
        List<List<String>> commands = new ArrayList<>();
        if (source.startsWith("s3://")) {
            commands.add(List.of("aws", "s3", "sync", source, target.toString()));
        } else if (isWindows()) {
            commands.add(List.of("robocopy", source, target.toString(), "/MIR"));
        } else {
            commands.add(List.of("rsync", "-a", source.endsWith("/") ? source : source + "/", target.toString()));
        }
        OpsRecord record = record("data", "cache", name, currentUser(), source, execute ? "SYNCED" : "PLANNED", metadata);
        if (execute) {
            try {
                Files.createDirectories(target);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to create cache target: " + target, e);
            }
        }
        return new OpsPlan(record, commands, execute ? executeAll(commands) : List.of(), warningsForMissingExecute(execute));
    }

    public OpsPlan gdsPlan(String name, Path mount, String mode, boolean execute) {
        Map<String, String> metadata = mapOf(
                "mount", mount.toString(),
                "mode", mode,
                "execute", Boolean.toString(execute)
        );
        List<List<String>> commands = List.of(
                List.of("nvidia-fs", "stats"),
                List.of("mount", "-o", "ro,direct", mount.toString(), mount.toString())
        );
        OpsRecord record = record("data", "gds", name, currentUser(), mount.toString(), execute ? "APPLIED" : "PLANNED", metadata);
        return new OpsPlan(record, commands, execute ? executeAll(commands) : List.of(), warningsForMissingExecute(execute));
    }

    public OpsRecord datasetSnapshot(String name, String source, String version, Path mountPath) {
        return record("data", "snapshot", name, currentUser(), mountPath.toString(), "READY",
                mapOf("source", source, "version", version, "mountPath", mountPath.toString(), "readOnly", "true"));
    }

    public OpsPlan checkpointPush(String name, Path source, String destination, boolean execute) {
        List<List<String>> commands = destination.startsWith("s3://")
                ? List.of(List.of("aws", "s3", "sync", source.toString(), destination))
                : List.of(isWindows()
                ? List.of("robocopy", source.toString(), destination, "/MIR")
                : List.of("rsync", "-a", source.toString(), destination));
        OpsRecord record = record("data", "checkpoint", name, currentUser(), destination, execute ? "PUSHED" : "PLANNED",
                mapOf("source", source.toString(), "destination", destination, "execute", Boolean.toString(execute)));
        return new OpsPlan(record, commands, execute ? executeAll(commands) : List.of(), warningsForMissingExecute(execute));
    }

    public OpsPlan batchJob(String name, String allocationId, String command, String image, String engine, String gpus, String shmSize, boolean execute) {
        String selectedEngine = engine == null || engine.isBlank() ? "docker" : engine;
        String selectedGpus = gpus == null || gpus.isBlank() ? "all" : gpus;
        String selectedShm = shmSize == null || shmSize.isBlank() ? "16g" : shmSize;
        requireActiveAllocationIfPresent(allocationId, "allocation-id");
        validateBatchJob(name, command, selectedEngine, selectedGpus, selectedShm);
        Map<String, String> metadata = mapOf("allocationId", allocationId, "command", command, "image", image, "engine", selectedEngine, "gpus", selectedGpus, "shmSize", selectedShm, "execute", Boolean.toString(execute));
        List<List<String>> commands = new ArrayList<>();
        if (image != null && !image.isBlank()) {
            if ("apptainer".equalsIgnoreCase(selectedEngine) || "singularity".equalsIgnoreCase(selectedEngine)) {
                commands.add(List.of(selectedEngine, "exec", "--nv", "--writable-tmpfs", image, "sh", "-lc", command));
            } else {
                commands.add(List.of("docker", "run", "--rm", "--gpus", selectedGpus, "--shm-size", selectedShm, image, "sh", "-lc", command));
            }
        } else {
            commands.add(shellCommand(command));
        }
        OpsRecord record = record("job", "batch", name, currentUser(), allocationId, execute ? "SUBMITTED" : "PLANNED", metadata);
        if (execute && (image == null || image.isBlank())) {
            runtimeWorkerService.register(record.id(), blankToNull(allocationId), null, currentUser(), command, null, Map.of(), null, null, null, 3, 1440, null);
            runtimeWorkerService.start(record.id());
        }
        return new OpsPlan(record, commands, execute && image != null && !image.isBlank() ? executeAll(commands) : List.of(), warningsForMissingExecute(execute));
    }

    public OpsRecord telemetryPolicy(String name, int intervalSec, int retentionHours, Path path) {
        return record("observe", "telemetry", name, currentUser(), path == null ? "stdout" : path.toString(), "ACTIVE",
                mapOf("intervalSec", Integer.toString(intervalSec), "retentionHours", Integer.toString(retentionHours), "path", path == null ? "" : path.toString()));
    }

    public OpsPlan session(String name, String allocationId, String kind, int port, boolean execute) {
        requireActiveAllocationIfPresent(allocationId, "allocation-id");
        String command = switch (kind.toLowerCase(Locale.ROOT)) {
            case "jupyter" -> "jupyter lab --ip 0.0.0.0 --port " + port;
            case "vscode" -> "code tunnel --accept-server-license-terms";
            case "ssh" -> "sshd -D -p " + port;
            default -> throw new IllegalArgumentException("session kind must be jupyter, vscode, or ssh");
        };
        OpsRecord record = record("job", "session", name, currentUser(), allocationId, execute ? "RUNNING" : "PLANNED",
                mapOf("allocationId", allocationId, "kind", kind, "port", Integer.toString(port), "command", command));
        if (execute) {
            runtimeWorkerService.register(record.id(), blankToNull(allocationId), null, currentUser(), command, null, Map.of(), null, null, null, 3, 480, null);
            runtimeWorkerService.start(record.id());
        }
        return new OpsPlan(record, List.of(shellCommand(command)), List.of(), warningsForMissingExecute(execute));
    }

    public OpsRecord alert(String name, String channel, String target, String event, String template) {
        return record("observe", "alert", name, currentUser(), target, "ACTIVE",
                mapOf("channel", channel, "event", event, "template", template));
    }

    public OpsPlan profile(String name, String allocationId, String tool, String command, boolean execute) {
        requireActiveAllocationIfPresent(allocationId, "allocation-id");
        List<String> wrapped = switch (tool.toLowerCase(Locale.ROOT)) {
            case "nsys" -> List.of("nsys", "profile", "-o", name, command);
            case "ncu" -> List.of("ncu", "--target-processes", "all", command);
            default -> shellCommand(command);
        };
        OpsRecord record = record("observe", "profile", name, currentUser(), allocationId, execute ? "RUNNING" : "PLANNED",
                mapOf("tool", tool, "command", command, "execute", Boolean.toString(execute)));
        return new OpsPlan(record, List.of(wrapped), execute ? executeAll(List.of(wrapped)) : List.of(), warningsForMissingExecute(execute));
    }

    public OpsRecord secretRef(String name, String provider, String ref, String envName) {
        return record("secret", "ref", name, currentUser(), ref, "ACTIVE",
                mapOf("provider", provider, "ref", ref, "env", envName, "storedValue", "false"));
    }

    public String renderSecretEnv(OpsRecord record, String format) {
        String envName = record.metadata().getOrDefault("env", record.name().toUpperCase(Locale.ROOT).replace('-', '_'));
        String ref = record.metadata().getOrDefault("ref", record.target());
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "cmd" -> "set " + envName + "=%" + envName + "%";
            case "json" -> "{\"" + envName + "\":\"" + ref + "\"}";
            default -> "export " + envName + "=\"${" + envName + ":-" + ref + "}\"";
        };
    }

    public Path writePythonSdk(Path output) {
        String code = """
                import json
                import subprocess

                class GpumClient:
                    def __init__(self, binary="gpum", db=None):
                        self.binary = binary
                        self.db = db

                    def run(self, *args):
                        cmd = [self.binary]
                        if self.db:
                            cmd += ["--db", self.db]
                        cmd += list(args)
                        return subprocess.run(cmd, text=True, capture_output=True, check=False)

                    def allocation_env(self, allocation_id):
                        result = self.run("integration", "ai", "env", "--allocation-id", allocation_id, "--format", "json")
                        if result.returncode != 0:
                            raise RuntimeError(result.stderr or result.stdout)
                        return json.loads(result.stdout)
        """;
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, code);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to write Python SDK: " + output, e);
        }
        return output;
    }

    private List<CommandResult> executeAll(List<List<String>> commands) {
        List<CommandResult> results = new ArrayList<>();
        for (List<String> command : commands) {
            results.add(commandExecutor.execute(command));
        }
        return results;
    }

    private void validateBatchJob(String name, String command, String engine, String gpus, String shmSize) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("job name must not be blank");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("job command must not be blank");
        }
        if (!Set.of("docker", "apptainer", "singularity").contains(engine.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("engine must be docker, apptainer, or singularity");
        }
        if (gpus == null || gpus.isBlank()) {
            throw new IllegalArgumentException("gpus must not be blank");
        }
        long shmMb = parseSizeMb(shmSize);
        int maxShmGb = safetyInt("maxJobShmGb", 512);
        if (shmMb < 64L) {
            throw new IllegalArgumentException("shm-size must be at least 64m for AI dataloaders");
        }
        if (shmMb > maxShmGb * 1024L) {
            throw new IllegalArgumentException("shm-size exceeds safety policy maxJobShmGb=" + maxShmGb);
        }
    }

    private void requireActiveAllocationIfPresent(String allocationId, String field) {
        if (allocationId == null || allocationId.isBlank()) {
            return;
        }
        requireActiveAllocation(allocationId, field);
    }

    private AllocationRecord requireActiveAllocation(String allocationId, String field) {
        if (allocationId == null || allocationId.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        AllocationRecord allocation = allocationRepository.findById(allocationId)
                .orElseThrow(() -> new IllegalArgumentException("Allocation not found: " + allocationId));
        if (allocation.status() != AllocationStatus.ACTIVE) {
            throw new IllegalArgumentException("Allocation is not active: " + allocationId + " status=" + allocation.status());
        }
        return allocation;
    }

    private int safetyInt(String key, int fallback) {
        List<OpsRecord> records = opsRepository.list("system", "safety-policy");
        if (records.isEmpty()) {
            return fallback;
        }
        String value = records.getFirst().metadata().get(key);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private long parseSizeMb(String value) {
        if (value == null || value.isBlank()) {
            return 16L * 1024L;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1L;
        if (normalized.endsWith("g") || normalized.endsWith("gb")) {
            multiplier = 1024L;
            normalized = normalized.replaceAll("gb?$", "");
        } else if (normalized.endsWith("m") || normalized.endsWith("mb")) {
            normalized = normalized.replaceAll("mb?$", "");
        }
        try {
            return Long.parseLong(normalized.trim()) * multiplier;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("shm-size must be a number with optional m/mb/g/gb suffix");
        }
    }

    private List<String> warningsForMissingExecute(boolean execute) {
        return execute ? List.of() : List.of("dry-run only; re-run with --execute to run external commands");
    }

    private List<String> shellCommand(String command) {
        return isWindows() ? List.of("cmd", "/c", command) : List.of("sh", "-lc", command);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String currentUser() {
        return System.getProperty("user.name", "unknown");
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private Map<String, String> mapOf(String... values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(values[i], values[i + 1] == null ? "" : values[i + 1]);
        }
        return map;
    }

    public record OpsPlan(OpsRecord record, List<List<String>> commands, List<CommandResult> results, List<String> warnings) {
    }

    public record FairShareReport(String owner, int windowHours, int allocations, double gpuHours, double score) {
    }

    public record CostEstimate(String owner, String gpuModel, int gpus, double hours, double ratePerGpuHour, double estimatedCost) {
    }
}
