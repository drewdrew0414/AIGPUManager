package com.drewdrew1.core.service;

import com.drewdrew1.core.config.GpumConfig;
import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.AllocationStatus;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.core.model.InterconnectType;
import com.drewdrew1.core.repository.AllocationRepository;
import com.drewdrew1.core.repository.InventoryRepository;
import com.drewdrew1.infra.executor.CommandExecutionException;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Applies guarded vendor GPU control commands with conservative safety checks. */
public class GpuControlService {
    private static final int MAX_REASONABLE_POWER_LIMIT_W = 2000;

    private final CommandExecutor commandExecutor;
    private final InventoryRepository inventoryRepository;
    private final AllocationRepository allocationRepository;
    private final SystemInfoService systemInfoService;
    private final GpumConfig config;

    public GpuControlService(
            CommandExecutor commandExecutor,
            InventoryRepository inventoryRepository,
            AllocationRepository allocationRepository,
            SystemInfoService systemInfoService,
            GpumConfig config
    ) {
        this.commandExecutor = commandExecutor;
        this.inventoryRepository = inventoryRepository;
        this.allocationRepository = allocationRepository;
        this.systemInfoService = systemInfoService;
        this.config = config;
    }

    public ControlResult applySettings(GpuDevice gpu, SetRequest request) {
        validateLocalWritableGpu(gpu);
        requireHardwareWriteEnabled(request.apply());
        forbidActiveAllocations(gpu, request.allowAllocated());

        List<List<String>> commands = new ArrayList<>();
        if (request.powerLimit() != null) {
            validatePowerLimit(gpu, request.powerLimit());
            commands.add(buildPowerLimitCommand(gpu, request.powerLimit()));
        }
        if (request.computeMode() != null) {
            commands.add(buildComputeModeCommand(gpu, request.computeMode()));
        }
        if (request.eccMode() != null) {
            if (!request.allowRebootRequired()) {
                throw new IllegalArgumentException("ECC changes require --allow-reboot-required.");
            }
            commands.add(buildEccCommand(gpu, request.eccMode()));
        }
        if (request.clockFix() != null) {
            throw new IllegalArgumentException("clock-fix apply is disabled until safe clock bound discovery is implemented.");
        }
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("No supported hardware write operation was selected.");
        }

        List<String> outputs = new ArrayList<>();
        for (List<String> command : commands) {
            outputs.add(executeRequired(command));
        }
        return new ControlResult(gpu.vendor(), gpu.nodeHostname(), gpu.deviceId(), commands, outputs);
    }

    public ControlResult resetGpu(GpuDevice gpu, ResetRequest request) {
        validateLocalWritableGpu(gpu);
        requireHardwareWriteEnabled(request.apply());
        forbidActiveAllocations(gpu, false);
        if (gpu.integratedGraphics()) {
            throw new IllegalArgumentException("Reset is blocked for integrated GPUs.");
        }
        if (gpu.interconnectType() != InterconnectType.PCIE && !request.allowLinkedReset()) {
            throw new IllegalArgumentException("Reset is blocked for linked GPUs unless --allow-linked-reset is set.");
        }
        if (request.hardReset()) {
            throw new IllegalArgumentException("Hard reset is intentionally blocked. Use vendor tooling directly after manual drain.");
        }

        List<String> command = buildResetCommand(gpu);
        String output = executeRequired(command);
        return new ControlResult(gpu.vendor(), gpu.nodeHostname(), gpu.deviceId(), List.of(command), List.of(output));
    }

    public ControlPreview previewSettings(GpuDevice gpu, SetRequest request) {
        List<String> blockers = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        previewLocalWritableGpu(gpu, blockers);
        previewActiveAllocation(gpu, request.allowAllocated(), blockers);

        List<List<String>> commands = new ArrayList<>();
        if (request.powerLimit() != null) {
            if (request.powerLimit() <= 0 || request.powerLimit() > MAX_REASONABLE_POWER_LIMIT_W) {
                blockers.add("power-limit must be between 1 and " + MAX_REASONABLE_POWER_LIMIT_W + " W");
            } else if (gpu.vendor() == GpuVendor.INTEL && gpu.integratedGraphics()) {
                blockers.add("power limit changes are blocked for integrated Intel GPUs");
            } else {
                commands.add(buildPowerLimitCommand(gpu, request.powerLimit()));
                notes.add("Device min/max power bounds will be verified again at apply time.");
            }
        }
        if (request.computeMode() != null) {
            try {
                commands.add(buildComputeModeCommand(gpu, request.computeMode()));
            } catch (IllegalArgumentException e) {
                blockers.add(e.getMessage());
            }
        }
        if (request.eccMode() != null) {
            if (!request.allowRebootRequired()) {
                blockers.add("ECC changes require --allow-reboot-required");
            } else {
                try {
                    commands.add(buildEccCommand(gpu, request.eccMode()));
                    notes.add("ECC changes may require a reset or reboot before they are fully effective.");
                } catch (IllegalArgumentException e) {
                    blockers.add(e.getMessage());
                }
            }
        }
        if (request.clockFix() != null) {
            blockers.add("clock-fix apply is disabled until safe clock bound discovery is implemented");
        }
        if (commands.isEmpty() && blockers.isEmpty()) {
            blockers.add("No supported hardware write operation was selected.");
        }
        notes.add("Apply requires --apply plus GPUM_ENABLE_HARDWARE_WRITE=1 or -Dgpum.enableHardwareWrite=true.");
        return new ControlPreview(gpu.vendor(), gpu.nodeHostname(), gpu.deviceId(), commands, notes, blockers);
    }

    public ControlPreview previewReset(GpuDevice gpu, ResetRequest request) {
        List<String> blockers = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        previewLocalWritableGpu(gpu, blockers);
        previewActiveAllocation(gpu, false, blockers);
        if (gpu.integratedGraphics()) {
            blockers.add("reset is blocked for integrated GPUs");
        }
        if (gpu.interconnectType() != InterconnectType.PCIE && !request.allowLinkedReset()) {
            blockers.add("reset is blocked for linked GPUs unless --allow-linked-reset is set");
        }
        if (request.hardReset()) {
            blockers.add("hard reset is intentionally blocked");
        }

        List<List<String>> commands = new ArrayList<>();
        if (blockers.isEmpty()) {
            commands.add(buildResetCommand(gpu));
        }
        notes.add("Apply requires --apply plus GPUM_ENABLE_HARDWARE_WRITE=1 or -Dgpum.enableHardwareWrite=true.");
        notes.add("Reset should only be attempted after the node is drained and the workload owner has been notified.");
        return new ControlPreview(gpu.vendor(), gpu.nodeHostname(), gpu.deviceId(), commands, notes, blockers);
    }

    private void validateLocalWritableGpu(GpuDevice gpu) {
        inventoryRepository.initialize();
        String localHostname = systemInfoService.localNodeInventory().hostname();
        if (!localHostname.equalsIgnoreCase(gpu.nodeHostname())) {
            throw new IllegalArgumentException("Hardware write is only allowed on the local node. Selected GPU belongs to " + gpu.nodeHostname());
        }
        if (!gpu.supportsCompute()) {
            throw new IllegalArgumentException("GPU is not marked as compute-capable.");
        }
    }

    private void previewLocalWritableGpu(GpuDevice gpu, List<String> blockers) {
        inventoryRepository.initialize();
        String localHostname = systemInfoService.localNodeInventory().hostname();
        if (!localHostname.equalsIgnoreCase(gpu.nodeHostname())) {
            blockers.add("hardware write is only allowed on the local node; selected GPU belongs to " + gpu.nodeHostname());
        }
        if (!gpu.supportsCompute()) {
            blockers.add("GPU is not marked as compute-capable");
        }
    }

    private void requireHardwareWriteEnabled(boolean apply) {
        if (!apply) {
            throw new IllegalArgumentException("Hardware write is disabled by default. Re-run with --apply.");
        }
        if (!"1".equals(System.getenv("GPUM_ENABLE_HARDWARE_WRITE"))
                && !Boolean.getBoolean("gpum.enableHardwareWrite")) {
            throw new IllegalArgumentException("Set GPUM_ENABLE_HARDWARE_WRITE=1 or -Dgpum.enableHardwareWrite=true to allow hardware mutation.");
        }
    }

    private void forbidActiveAllocations(GpuDevice gpu, boolean allowAllocated) {
        allocationRepository.initialize();
        if (allowAllocated) {
            return;
        }
        String gpuKey = gpu.uuid() != null && !gpu.uuid().isBlank()
                ? "uuid:" + gpu.uuid()
                : gpu.nodeHostname() + ":" + gpu.deviceId();
        for (AllocationRecord record : allocationRepository.listActive()) {
            if (record.status() != AllocationStatus.ACTIVE) {
                continue;
            }
            boolean matches = record.devices().stream().anyMatch(device ->
                    (device.uuid() != null && !device.uuid().isBlank() && ("uuid:" + device.uuid()).equalsIgnoreCase(gpuKey))
                            || (device.uuid() == null || device.uuid().isBlank())
                            && (device.nodeHostname() + ":" + device.deviceId()).equalsIgnoreCase(gpuKey));
            if (matches) {
                throw new IllegalArgumentException("GPU has an active allocation: " + record.id() + ". Release it first or use --allow-allocated.");
            }
        }
    }

    private void previewActiveAllocation(GpuDevice gpu, boolean allowAllocated, List<String> blockers) {
        allocationRepository.initialize();
        if (allowAllocated) {
            return;
        }
        String gpuKey = gpu.uuid() != null && !gpu.uuid().isBlank()
                ? "uuid:" + gpu.uuid()
                : gpu.nodeHostname() + ":" + gpu.deviceId();
        for (AllocationRecord record : allocationRepository.listActive()) {
            boolean matches = record.devices().stream().anyMatch(device ->
                    (device.uuid() != null && !device.uuid().isBlank() && ("uuid:" + device.uuid()).equalsIgnoreCase(gpuKey))
                            || (device.uuid() == null || device.uuid().isBlank())
                            && (device.nodeHostname() + ":" + device.deviceId()).equalsIgnoreCase(gpuKey));
            if (matches) {
                blockers.add("GPU has an active allocation: " + record.id());
                return;
            }
        }
    }

    private void validatePowerLimit(GpuDevice gpu, int requestedPowerLimit) {
        if (requestedPowerLimit <= 0 || requestedPowerLimit > MAX_REASONABLE_POWER_LIMIT_W) {
            throw new IllegalArgumentException("power-limit must be between 1 and " + MAX_REASONABLE_POWER_LIMIT_W + " W");
        }
        switch (gpu.vendor()) {
            case NVIDIA -> validateNvidiaPowerLimit(gpu, requestedPowerLimit);
            case AMD -> validateAmdPowerLimit(gpu, requestedPowerLimit);
            case INTEL -> {
                if (gpu.integratedGraphics()) {
                    throw new IllegalArgumentException("Power limit changes are blocked for integrated Intel GPUs.");
                }
            }
        }
    }

    private void validateNvidiaPowerLimit(GpuDevice gpu, int requestedPowerLimit) {
        try {
            CommandResult result = commandExecutor.execute(List.of(
                    config.getTools().getNvidiaSmi(),
                    "-q",
                    "-d",
                    "POWER",
                    "-i",
                    gpuSelector(gpu)
            ));
            if (!result.isSuccess()) {
                throw new IllegalArgumentException("Failed to query NVIDIA power limits: " + safeError(result));
            }
            Integer min = parseLabeledInteger(result.stdout(), "Min Power Limit");
            Integer max = parseLabeledInteger(result.stdout(), "Max Power Limit");
            if (min != null && requestedPowerLimit < min) {
                throw new IllegalArgumentException("Requested power limit is below device minimum: " + min + " W");
            }
            if (max != null && requestedPowerLimit > max) {
                throw new IllegalArgumentException("Requested power limit exceeds device maximum: " + max + " W");
            }
        } catch (CommandExecutionException e) {
            throw new IllegalArgumentException("Failed to query NVIDIA power limits: " + e.getMessage(), e);
        }
    }

    private void validateAmdPowerLimit(GpuDevice gpu, int requestedPowerLimit) {
        try {
            CommandResult result = commandExecutor.execute(List.of(
                    config.getTools().getAmdSmi(),
                    "static",
                    "-g",
                    gpuSelector(gpu),
                    "--limit"
            ));
            if (!result.isSuccess()) {
                throw new IllegalArgumentException("Failed to query AMD power limits: " + safeError(result));
            }
            Integer min = parseLabeledInteger(result.stdout(), "MIN_POWER");
            Integer max = parseLabeledInteger(result.stdout(), "MAX_POWER");
            if (min != null && requestedPowerLimit < min) {
                throw new IllegalArgumentException("Requested power limit is below device minimum: " + min + " W");
            }
            if (max != null && requestedPowerLimit > max) {
                throw new IllegalArgumentException("Requested power limit exceeds device maximum: " + max + " W");
            }
        } catch (CommandExecutionException e) {
            throw new IllegalArgumentException("Failed to query AMD power limits: " + e.getMessage(), e);
        }
    }

    private List<String> buildPowerLimitCommand(GpuDevice gpu, int powerLimit) {
        return switch (gpu.vendor()) {
            case NVIDIA -> List.of(
                    config.getTools().getNvidiaSmi(),
                    "-i",
                    gpuSelector(gpu),
                    "-pl",
                    Integer.toString(powerLimit)
            );
            case AMD -> List.of(
                    config.getTools().getAmdSmi(),
                    "set",
                    "-g",
                    gpuSelector(gpu),
                    "--power-cap",
                    "ppt0",
                    Integer.toString(powerLimit)
            );
            case INTEL -> List.of(
                    config.getTools().getXpuSmi(),
                    "config",
                    "-d",
                    gpuSelector(gpu),
                    "--powerlimit",
                    Integer.toString(powerLimit)
            );
        };
    }

    private List<String> buildComputeModeCommand(GpuDevice gpu, String computeMode) {
        if (gpu.vendor() != GpuVendor.NVIDIA) {
            throw new IllegalArgumentException("compute-mode apply is currently supported only for NVIDIA GPUs.");
        }
        String mode = switch (computeMode.toLowerCase(Locale.ROOT)) {
            case "default" -> "DEFAULT";
            case "exclusive_process" -> "EXCLUSIVE_PROCESS";
            default -> throw new IllegalArgumentException("Unsupported compute mode: " + computeMode);
        };
        if (gpu.supportsMig()) {
            throw new IllegalArgumentException("compute-mode changes are blocked on MIG-capable inventory until MIG state is verified.");
        }
        return List.of(
                config.getTools().getNvidiaSmi(),
                "-i",
                gpuSelector(gpu),
                "-c",
                mode
        );
    }

    private List<String> buildEccCommand(GpuDevice gpu, String eccMode) {
        return switch (gpu.vendor()) {
            case NVIDIA -> List.of(
                    config.getTools().getNvidiaSmi(),
                    "-i",
                    gpuSelector(gpu),
                    "-e",
                    "on".equalsIgnoreCase(eccMode) ? "1" : "0"
            );
            case INTEL -> List.of(
                    config.getTools().getXpuSmi(),
                    "config",
                    "-d",
                    gpuSelector(gpu),
                    "--memoryecc",
                    "on".equalsIgnoreCase(eccMode) ? "1" : "0"
            );
            case AMD -> throw new IllegalArgumentException("ECC mode apply is not currently wired for AMD CLI.");
        };
    }

    private List<String> buildResetCommand(GpuDevice gpu) {
        return switch (gpu.vendor()) {
            case NVIDIA -> List.of(
                    config.getTools().getNvidiaSmi(),
                    "-i",
                    gpuSelector(gpu),
                    "-r"
            );
            case AMD -> List.of(
                    config.getTools().getAmdSmi(),
                    "reset",
                    "-g",
                    gpuSelector(gpu),
                    "--gpureset"
            );
            case INTEL -> List.of(
                    config.getTools().getXpuSmi(),
                    "config",
                    "-d",
                    gpuSelector(gpu),
                    "--reset"
            );
        };
    }

    private String executeRequired(List<String> command) {
        try {
            CommandResult result = commandExecutor.execute(command);
            if (!result.isSuccess()) {
                throw new IllegalStateException("Command failed: " + String.join(" ", command) + " :: " + safeError(result));
            }
            return !result.stdout().isBlank() ? result.stdout().trim() : result.stderr().trim();
        } catch (CommandExecutionException e) {
            throw new IllegalStateException("Command execution failed: " + String.join(" ", command) + " :: " + e.getMessage(), e);
        }
    }

    private String gpuSelector(GpuDevice gpu) {
        if (gpu.uuid() != null && !gpu.uuid().isBlank() && gpu.vendor() != GpuVendor.INTEL) {
            return gpu.uuid();
        }
        if (gpu.deviceId() != null && !gpu.deviceId().isBlank()) {
            return gpu.deviceId();
        }
        if (gpu.uuid() != null && !gpu.uuid().isBlank()) {
            return gpu.uuid();
        }
        throw new IllegalArgumentException("GPU selector is unavailable for this device.");
    }

    private Integer parseLabeledInteger(String output, String label) {
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.toLowerCase(Locale.ROOT).startsWith(label.toLowerCase(Locale.ROOT))) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            String value = colon >= 0 ? trimmed.substring(colon + 1) : trimmed.substring(label.length());
            String normalized = value.replaceAll("[^0-9-]", "");
            if (!normalized.isBlank()) {
                return Integer.parseInt(normalized);
            }
        }
        return null;
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

    /** Describes one guarded GPU setting mutation request. */
    public record SetRequest(
            boolean apply,
            boolean allowAllocated,
            boolean allowRebootRequired,
            Integer powerLimit,
            String clockFix,
            String eccMode,
            String computeMode
    ) {
    }

    /** Describes one guarded GPU reset request. */
    public record ResetRequest(
            boolean apply,
            boolean hardReset,
            boolean allowLinkedReset
    ) {
    }

    /** Returns the vendor commands that were executed and their captured outputs. */
    public record ControlResult(
            GpuVendor vendor,
            String nodeHostname,
            String deviceId,
            List<List<String>> commands,
            List<String> outputs
    ) {
    }

    /** Describes dry-run command previews and the safety interlocks that currently block execution. */
    public record ControlPreview(
            GpuVendor vendor,
            String nodeHostname,
            String deviceId,
            List<List<String>> commands,
            List<String> notes,
            List<String> blockers
    ) {
    }
}
