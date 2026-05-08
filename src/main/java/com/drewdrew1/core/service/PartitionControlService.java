package com.drewdrew1.core.service;

import com.drewdrew1.core.config.GpumConfig;
import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.AllocationStatus;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuPartitionRecord;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.core.repository.AllocationRepository;
import com.drewdrew1.core.repository.GovernanceRepository;
import com.drewdrew1.core.repository.InventoryRepository;
import com.drewdrew1.infra.executor.CommandExecutionException;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies and rolls back NVIDIA MIG partitions while keeping DB records coherent. */
public class PartitionControlService {
    private static final Pattern GPU_INSTANCE_PATTERN = Pattern.compile("GPU instance ID\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPUTE_INSTANCE_PATTERN = Pattern.compile("Compute instance ID\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    private final GovernanceRepository governanceRepository;
    private final InventoryRepository inventoryRepository;
    private final AllocationRepository allocationRepository;
    private final CommandExecutor commandExecutor;
    private final SystemInfoService systemInfoService;
    private final GpuProcessService gpuProcessService;
    private final GpumConfig config;

    public PartitionControlService(
            GovernanceRepository governanceRepository,
            InventoryRepository inventoryRepository,
            AllocationRepository allocationRepository,
            CommandExecutor commandExecutor,
            SystemInfoService systemInfoService,
            GpuProcessService gpuProcessService,
            GpumConfig config
    ) {
        this.governanceRepository = governanceRepository;
        this.inventoryRepository = inventoryRepository;
        this.allocationRepository = allocationRepository;
        this.commandExecutor = commandExecutor;
        this.systemInfoService = systemInfoService;
        this.gpuProcessService = gpuProcessService;
        this.config = config;
    }

    public List<GpuPartitionRecord> createNvidiaMigPartitions(
            GpuDevice gpu,
            String profile,
            int count,
            boolean killProcesses,
            String approvalId
    ) {
        validateLocalMigTarget(gpu);
        forbidActiveAllocations(gpu);
        if (killProcesses) {
            gpuProcessService.cleanupGpuProcesses(gpu, true);
        } else if (!gpuProcessService.listProcessesForGpu(gpu).isEmpty()) {
            throw new IllegalArgumentException("GPU still has running local processes. Re-run with --kill-processes after manual review.");
        }

        List<GpuPartitionRecord> transientRecords = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                Set<String> beforeGi = listGpuInstanceIds(gpu);
                executeRequired(List.of(
                        config.getTools().getNvidiaSmi(),
                        "mig",
                        "-cgi",
                        profile,
                        "-C",
                        "-i",
                        gpuSelector(gpu)
                ));
                Set<String> afterGi = listGpuInstanceIds(gpu);
                Set<String> newGi = new LinkedHashSet<>(afterGi);
                newGi.removeAll(beforeGi);
                if (newGi.size() != 1) {
                    throw new IllegalStateException("Expected exactly one new GPU instance, found " + newGi.size());
                }
                String gpuInstanceId = newGi.iterator().next();
                Set<String> computeIds = listComputeInstanceIds(gpu, gpuInstanceId);
                transientRecords.add(new GpuPartitionRecord(
                        "part-" + UUID.randomUUID(),
                        gpu.nodeHostname(),
                        gpu.deviceId(),
                        gpu.model(),
                        profile,
                        "ACTIVE",
                        true,
                        GpuVendor.NVIDIA.name(),
                        gpuInstanceId,
                        computeIds.isEmpty() ? null : String.join(",", computeIds),
                        approvalId,
                        Instant.now()
                ));
            }
        } catch (RuntimeException e) {
            rollbackHardware(gpu, transientRecords);
            throw e;
        }

        List<GpuPartitionRecord> persisted = new ArrayList<>();
        for (GpuPartitionRecord record : transientRecords) {
            persisted.add(governanceRepository.createPartition(record));
        }
        return persisted;
    }

    public int destroyPartitions(List<GpuPartitionRecord> records, boolean killProcesses) {
        int removed = 0;
        for (GpuPartitionRecord record : records) {
            if (!record.hardwareApplied() || !GpuVendor.NVIDIA.name().equalsIgnoreCase(record.hardwareVendor())) {
                removed += governanceRepository.deletePartitionById(record.id());
                continue;
            }
            GpuDevice gpu = findGpu(record.nodeHostname(), record.gpuDeviceId());
            validateLocalMigTarget(gpu);
            forbidActiveAllocations(gpu);
            if (killProcesses) {
                gpuProcessService.cleanupGpuProcesses(gpu, true);
            } else if (!gpuProcessService.listProcessesForGpu(gpu).isEmpty()) {
                throw new IllegalArgumentException("GPU still has running local processes. Re-run with --kill-processes after manual review.");
            }
            destroyHardwareRecord(gpu, record);
            removed += governanceRepository.deletePartitionById(record.id());
        }
        return removed;
    }

    private void validateLocalMigTarget(GpuDevice gpu) {
        inventoryRepository.initialize();
        String localHostname = systemInfoService.localNodeInventory().hostname();
        if (!localHostname.equalsIgnoreCase(gpu.nodeHostname())) {
            throw new IllegalArgumentException("MIG apply is allowed only on the local node.");
        }
        if (gpu.vendor() != GpuVendor.NVIDIA) {
            throw new IllegalArgumentException("Actual partition apply is currently supported only for NVIDIA MIG.");
        }
        if (!gpu.supportsMig()) {
            throw new IllegalArgumentException("GPU does not advertise MIG capability.");
        }
        if (!"1".equals(System.getenv("GPUM_ENABLE_HARDWARE_WRITE"))
                && !Boolean.getBoolean("gpum.enableHardwareWrite")) {
            throw new IllegalArgumentException("Set GPUM_ENABLE_HARDWARE_WRITE=1 or -Dgpum.enableHardwareWrite=true to allow hardware mutation.");
        }
    }

    private void forbidActiveAllocations(GpuDevice gpu) {
        allocationRepository.initialize();
        for (AllocationRecord record : allocationRepository.listActive()) {
            if (record.status() != AllocationStatus.ACTIVE) {
                continue;
            }
            boolean matches = record.devices().stream().anyMatch(device ->
                    gpu.nodeHostname().equalsIgnoreCase(device.nodeHostname())
                            && ((gpu.uuid() != null && gpu.uuid().equalsIgnoreCase(device.uuid()))
                            || (gpu.deviceId() != null && gpu.deviceId().equalsIgnoreCase(device.deviceId()))));
            if (matches) {
                throw new IllegalArgumentException("GPU has an active allocation: " + record.id());
            }
        }
    }

    private void rollbackHardware(GpuDevice gpu, List<GpuPartitionRecord> records) {
        List<GpuPartitionRecord> ordered = new ArrayList<>(records);
        ordered.sort(Comparator.comparing(GpuPartitionRecord::createdAt).reversed());
        for (GpuPartitionRecord record : ordered) {
            try {
                destroyHardwareRecord(gpu, record);
            } catch (Exception ignored) {
            }
        }
    }

    private void destroyHardwareRecord(GpuDevice gpu, GpuPartitionRecord record) {
        if (record.hardwareComputeInstanceIds() != null && !record.hardwareComputeInstanceIds().isBlank()) {
            executeRequired(List.of(
                    config.getTools().getNvidiaSmi(),
                    "mig",
                    "-dci",
                    "-ci",
                    record.hardwareComputeInstanceIds(),
                    "-gi",
                    record.hardwareGpuInstanceId(),
                    "-i",
                    gpuSelector(gpu)
            ));
        }
        executeRequired(List.of(
                config.getTools().getNvidiaSmi(),
                "mig",
                "-dgi",
                "-gi",
                record.hardwareGpuInstanceId(),
                "-i",
                gpuSelector(gpu)
        ));
    }

    private Set<String> listGpuInstanceIds(GpuDevice gpu) {
        CommandResult result = executeRequired(List.of(
                config.getTools().getNvidiaSmi(),
                "mig",
                "-lgi",
                "-i",
                gpuSelector(gpu)
        ));
        return parseIds(result.stdout(), GPU_INSTANCE_PATTERN);
    }

    private Set<String> listComputeInstanceIds(GpuDevice gpu, String gpuInstanceId) {
        CommandResult result = executeRequired(List.of(
                config.getTools().getNvidiaSmi(),
                "mig",
                "-lci",
                "-gi",
                gpuInstanceId,
                "-i",
                gpuSelector(gpu)
        ));
        return parseIds(result.stdout(), COMPUTE_INSTANCE_PATTERN);
    }

    private Set<String> parseIds(String output, Pattern pattern) {
        Set<String> ids = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
        }
        return ids;
    }

    private CommandResult executeRequired(List<String> command) {
        try {
            CommandResult result = commandExecutor.execute(command);
            if (!result.isSuccess()) {
                throw new IllegalStateException("Command failed: " + String.join(" ", command) + " :: " + safeError(result));
            }
            return result;
        } catch (CommandExecutionException e) {
            throw new IllegalStateException("Command execution failed: " + String.join(" ", command) + " :: " + e.getMessage(), e);
        }
    }

    private GpuDevice findGpu(String hostname, String deviceId) {
        return inventoryRepository.listGpus().stream()
                .filter(gpu -> hostname.equalsIgnoreCase(gpu.nodeHostname()))
                .filter(gpu -> deviceId.equalsIgnoreCase(gpu.deviceId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("GPU not found in inventory: " + hostname + ":" + deviceId));
    }

    private String gpuSelector(GpuDevice gpu) {
        if (gpu.uuid() != null && !gpu.uuid().isBlank()) {
            return gpu.uuid();
        }
        if (gpu.deviceId() != null && !gpu.deviceId().isBlank()) {
            return gpu.deviceId();
        }
        throw new IllegalArgumentException("GPU selector is unavailable for this device.");
    }

    private String safeError(CommandResult result) {
        if (result.stderr() != null && !result.stderr().isBlank()) {
            return result.stderr().trim();
        }
        if (result.stdout() != null && !result.stdout().isBlank()) {
            return result.stdout().trim();
        }
        return "exit=" + result.exitCode();
    }
}
