package com.drewdrew1.core.service;

import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.RuntimeEvent;
import com.drewdrew1.core.model.RuntimeWorkerRecord;
import com.drewdrew1.core.repository.AllocationRepository;
import com.drewdrew1.core.repository.RuntimeRepository;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Manages registered AI worker processes and conservative restart/recycle actions. */
public class RuntimeWorkerService {
    private final RuntimeRepository runtimeRepository;
    private final AllocationRepository allocationRepository;

    public RuntimeWorkerService(RuntimeRepository runtimeRepository, AllocationRepository allocationRepository) {
        this.runtimeRepository = runtimeRepository;
        this.allocationRepository = allocationRepository;
    }

    public RuntimeWorkerRecord register(
            String id,
            String allocationId,
            String tenant,
            String owner,
            String command,
            String workingDirectory,
            Map<String, String> environment,
            String checkpointCommand,
            String restoreCommand,
            String oomRecoveryCommand,
            int maxRestarts,
            int maxLifetimeMinutes,
            Long memoryRestartMb
    ) {
        runtimeRepository.initialize();
        if (allocationId != null && !allocationId.isBlank()) {
            allocationRepository.findById(allocationId)
                    .orElseThrow(() -> new IllegalArgumentException("Allocation not found: " + allocationId));
        }
        String workerId = id == null || id.isBlank() ? "worker-" + UUID.randomUUID() : id.trim();
        Instant now = Instant.now();
        RuntimeWorkerRecord record = new RuntimeWorkerRecord(
                workerId,
                blankToNull(allocationId),
                blankToNull(tenant),
                owner,
                command,
                blankToNull(workingDirectory),
                environment == null ? Map.of() : new LinkedHashMap<>(environment),
                blankToNull(checkpointCommand),
                blankToNull(restoreCommand),
                blankToNull(oomRecoveryCommand),
                null,
                "REGISTERED",
                0,
                Math.max(0, maxRestarts),
                Math.max(1, maxLifetimeMinutes),
                memoryRestartMb,
                now,
                now,
                null
        );
        RuntimeWorkerRecord saved = runtimeRepository.upsertWorker(record);
        event(saved.id(), "REGISTER", "registered worker command");
        return saved;
    }

    public List<RuntimeWorkerRecord> list() {
        runtimeRepository.initialize();
        return runtimeRepository.listWorkers();
    }

    public Optional<RuntimeWorkerRecord> find(String id) {
        runtimeRepository.initialize();
        return runtimeRepository.findWorker(id);
    }

    public RuntimeWorkerRecord start(String id) {
        RuntimeWorkerRecord worker = findRequired(id);
        if (worker.pid() != null && ProcessHandle.of(worker.pid()).map(ProcessHandle::isAlive).orElse(false)) {
            return worker;
        }
        Process process = startProcess(worker.command(), worker.workingDirectory(), worker.environment());
        RuntimeWorkerRecord updated = updateState(worker, process.pid(), "RUNNING", worker.restartCount(), Instant.now());
        event(id, "START", "started pid=" + process.pid());
        return updated;
    }

    public RuntimeWorkerRecord stop(String id, boolean force) {
        RuntimeWorkerRecord worker = findRequired(id);
        if (worker.pid() != null) {
            ProcessHandle.of(worker.pid()).ifPresent(handle -> terminate(handle, force));
        }
        RuntimeWorkerRecord updated = updateState(worker, null, "STOPPED", worker.restartCount(), worker.startedAt());
        event(id, "STOP", "stopped worker force=" + force);
        return updated;
    }

    public RuntimeWorkerRecord restart(String id, boolean force, String reason) {
        RuntimeWorkerRecord worker = findRequired(id);
        if (worker.restartCount() >= worker.maxRestarts()) {
            throw new IllegalArgumentException("Worker restart limit reached: " + id);
        }
        if (worker.pid() != null) {
            ProcessHandle.of(worker.pid()).ifPresent(handle -> terminate(handle, force));
        }
        Process process = startProcess(worker.command(), worker.workingDirectory(), worker.environment());
        RuntimeWorkerRecord updated = updateState(worker, process.pid(), "RUNNING", worker.restartCount() + 1, Instant.now());
        event(id, "RESTART", "restarted pid=" + process.pid() + ", reason=" + safe(reason));
        return updated;
    }

    public List<RuntimeWorkerRecord> recycleDueWorkers(boolean execute) {
        List<RuntimeWorkerRecord> recycled = new ArrayList<>();
        Instant now = Instant.now();
        for (RuntimeWorkerRecord worker : runtimeRepository.listWorkers()) {
            if (!"RUNNING".equalsIgnoreCase(worker.status()) || worker.startedAt() == null) {
                continue;
            }
            long ageMin = Duration.between(worker.startedAt(), now).toMinutes();
            if (ageMin >= worker.maxLifetimeMinutes()) {
                if (execute) {
                    recycled.add(restart(worker.id(), false, "max lifetime reached"));
                } else {
                    recycled.add(worker);
                }
            }
        }
        return recycled;
    }

    public RuntimeWorkerRecord handleOom(String allocationId, String strategy, boolean execute) {
        List<RuntimeWorkerRecord> workers = runtimeRepository.listWorkersByAllocation(allocationId);
        if (workers.isEmpty()) {
            throw new IllegalArgumentException("No registered workers found for allocation: " + allocationId);
        }
        RuntimeWorkerRecord worker = workers.get(0);
        event(worker.id(), "OOM", "strategy=" + strategy + ", execute=" + execute);
        if (!execute) {
            return worker;
        }
        return switch (strategy.toLowerCase(java.util.Locale.ROOT)) {
            case "restart" -> restart(worker.id(), true, "oom");
            case "defrag" -> runOomRecovery(worker, execute);
            case "stop" -> stop(worker.id(), true);
            case "release" -> {
                allocationRepository.updateStatus(allocationId, com.drewdrew1.core.model.AllocationStatus.RELEASED, Instant.now());
                yield stop(worker.id(), true);
            }
            default -> throw new IllegalArgumentException("Unsupported OOM strategy: " + strategy);
        };
    }

    private RuntimeWorkerRecord runOomRecovery(RuntimeWorkerRecord worker, boolean execute) {
        if (worker.oomRecoveryCommand() == null || worker.oomRecoveryCommand().isBlank()) {
            throw new IllegalArgumentException("Worker does not define an OOM recovery command: " + worker.id());
        }
        if (execute) {
            runShell(worker.oomRecoveryCommand(), worker.workingDirectory(), worker.environment());
        }
        event(worker.id(), "OOM_RECOVERY", "defrag command " + (execute ? "completed" : "previewed"));
        return findRequired(worker.id());
    }

    public MigrationPlan migrationPlan(String workerId, String toNode) {
        RuntimeWorkerRecord worker = findRequired(workerId);
        boolean canExecute = worker.checkpointCommand() != null && worker.restoreCommand() != null;
        List<String> steps = new ArrayList<>();
        steps.add("drain source allocation workloads");
        steps.add("run checkpoint command");
        steps.add("move or create destination allocation on " + toNode);
        steps.add("run restore command on destination runtime");
        steps.add("stop old worker after restore validation");
        return new MigrationPlan(worker.id(), worker.allocationId(), toNode, canExecute, steps);
    }

    public MigrationPlan migrate(String workerId, String toNode, boolean execute) {
        MigrationPlan plan = migrationPlan(workerId, toNode);
        if (!execute) {
            return plan;
        }
        RuntimeWorkerRecord worker = findRequired(workerId);
        if (!plan.executable()) {
            throw new IllegalArgumentException("Worker does not define checkpoint and restore commands.");
        }
        runShell(worker.checkpointCommand(), worker.workingDirectory(), worker.environment());
        event(worker.id(), "CHECKPOINT", "checkpoint command completed");
        runShell(worker.restoreCommand(), worker.workingDirectory(), worker.environment());
        event(worker.id(), "RESTORE", "restore command completed on target=" + toNode);
        return plan;
    }

    public List<RuntimeEvent> listEvents(String workerId, int limit) {
        return runtimeRepository.listEvents(workerId, Math.max(1, limit));
    }

    private RuntimeWorkerRecord findRequired(String id) {
        return runtimeRepository.findWorker(id)
                .orElseThrow(() -> new IllegalArgumentException("Runtime worker not found: " + id));
    }

    private RuntimeWorkerRecord updateState(RuntimeWorkerRecord worker, Long pid, String status, int restartCount, Instant startedAt) {
        RuntimeWorkerRecord updated = new RuntimeWorkerRecord(
                worker.id(),
                worker.allocationId(),
                worker.tenant(),
                worker.owner(),
                worker.command(),
                worker.workingDirectory(),
                worker.environment(),
                worker.checkpointCommand(),
                worker.restoreCommand(),
                worker.oomRecoveryCommand(),
                pid,
                status,
                restartCount,
                worker.maxRestarts(),
                worker.maxLifetimeMinutes(),
                worker.memoryRestartMb(),
                worker.createdAt(),
                Instant.now(),
                startedAt
        );
        runtimeRepository.updateWorker(updated);
        return updated;
    }

    private Process startProcess(String command, String workingDirectory, Map<String, String> environment) {
        try {
            ProcessBuilder builder = shell(command);
            if (workingDirectory != null && !workingDirectory.isBlank()) {
                builder.directory(new File(workingDirectory));
            }
            builder.environment().putAll(environment);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            return builder.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start worker command: " + e.getMessage(), e);
        }
    }

    private void runShell(String command, String workingDirectory, Map<String, String> environment) {
        try {
            Process process = startProcess(command, workingDirectory, environment);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("Command failed with exit code " + exit + ": " + command);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Command interrupted: " + command, e);
        }
    }

    private ProcessBuilder shell(String command) {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return new ProcessBuilder("cmd", "/c", command);
        }
        return new ProcessBuilder("sh", "-lc", command);
    }

    private void terminate(ProcessHandle handle, boolean force) {
        handle.descendants().forEach(ProcessHandle::destroy);
        handle.destroy();
        if (force && handle.isAlive()) {
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            handle.destroyForcibly();
        }
    }

    private void event(String workerId, String type, String message) {
        runtimeRepository.appendEvent(new RuntimeEvent("runtime-event-" + UUID.randomUUID(), workerId, type, message, Instant.now()));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unspecified" : value;
    }

    /** Describes a safe checkpoint-based migration plan. */
    public record MigrationPlan(String workerId, String allocationId, String targetNode, boolean executable, List<String> steps) {
    }
}
