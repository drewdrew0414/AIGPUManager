package com.drewdrew1.infra.detector;

import com.drewdrew1.core.detector.DetectionResult;
import com.drewdrew1.core.detector.GpuDetector;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.HealthState;
import com.drewdrew1.core.model.InterconnectType;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.core.service.CapabilityResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.drewdrew1.infra.executor.CommandExecutionException;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detects Intel GPU inventory by parsing xpu-smi style output. */
public class IntelDetector implements GpuDetector {
    private static final long WINDOWS_INTEGRATED_SHARED_MEMORY_DIVISOR = 2L;

    private final CommandExecutor commandExecutor;
    private final CapabilityResolver capabilityResolver;
    private final String xpuSmiCommand;
    private final String powershellCommand;

    public IntelDetector(CommandExecutor commandExecutor, CapabilityResolver capabilityResolver, String xpuSmiCommand) {
        this(commandExecutor, capabilityResolver, xpuSmiCommand, "powershell");
    }

    public IntelDetector(
            CommandExecutor commandExecutor,
            CapabilityResolver capabilityResolver,
            String xpuSmiCommand,
            String powershellCommand
    ) {
        this.commandExecutor = commandExecutor;
        this.capabilityResolver = capabilityResolver;
        this.xpuSmiCommand = xpuSmiCommand;
        this.powershellCommand = powershellCommand;
    }

    @Override
    public DetectionResult detect(String hostname, Instant scannedAt) {
        DetectionResult xpuResult = detectViaXpuSmi(hostname, scannedAt);
        if (!xpuResult.devices().isEmpty()) {
            return xpuResult;
        }
        if (isWindows()) {
            DetectionResult fallback = detectViaWindowsDisplayAdapter(hostname, scannedAt);
            if (!fallback.devices().isEmpty()) {
                List<String> warnings = new ArrayList<>(xpuResult.warnings());
                warnings.addAll(fallback.warnings());
                return new DetectionResult(GpuVendor.INTEL, fallback.devices(), warnings);
            }
        }
        return xpuResult;
    }

    private DetectionResult detectViaXpuSmi(String hostname, Instant scannedAt) {
        try {
            CommandResult discovery = commandExecutor.execute(List.of(xpuSmiCommand, "discovery", "-j"));
            if (!discovery.isSuccess()) {
                return new DetectionResult(
                        GpuVendor.INTEL,
                        Collections.emptyList(),
                        List.of("Intel detector failed: " + discovery.stderr().trim())
                );
            }

            Map<String, Map<String, String>> merged = new LinkedHashMap<>(DetectorSupport.extractGpuMaps(discovery.stdout()));
            List<String> warnings = new ArrayList<>();

            for (Map<String, String> attributes : new ArrayList<>(merged.values())) {
                String selector = DetectorSupport.firstValue(attributes, "device_id", "pci_bdf_address", "pci_bdf");
                if (selector == null) {
                    continue;
                }
                mergeIfSuccess(merged, warnings, List.of(xpuSmiCommand, "discovery", "-d", selector, "-j"));
                mergeIfSuccess(merged, warnings, List.of(xpuSmiCommand, "stats", "-d", selector, "-j"));
                mergeIfSuccess(merged, warnings, List.of(xpuSmiCommand, "health", "-d", selector, "-j"));
            }

            return new DetectionResult(GpuVendor.INTEL, buildDevices(hostname, scannedAt, merged), warnings);
        } catch (CommandExecutionException e) {
            return new DetectionResult(
                    GpuVendor.INTEL,
                    Collections.emptyList(),
                    List.of("Intel detector unavailable: " + e.getMessage())
            );
        }
    }

    private DetectionResult detectViaWindowsDisplayAdapter(String hostname, Instant scannedAt) {
        try {
            CommandResult result = commandExecutor.execute(List.of(
                    powershellCommand,
                    "-NoProfile",
                    "-Command",
                    "Get-CimInstance Win32_VideoController | " +
                            "Select-Object Name,AdapterRAM,DriverVersion,PNPDeviceID,VideoProcessor | ConvertTo-Json -Compress"
            ));
            if (!result.isSuccess()) {
                return new DetectionResult(
                        GpuVendor.INTEL,
                        Collections.emptyList(),
                        List.of("Intel Windows fallback failed: " + result.stderr().trim())
                );
            }
            List<GpuDevice> devices = parseWindowsDisplayAdapters(hostname, scannedAt, result.stdout());
            return new DetectionResult(GpuVendor.INTEL, devices, List.of());
        } catch (CommandExecutionException e) {
            return new DetectionResult(
                    GpuVendor.INTEL,
                    Collections.emptyList(),
                    List.of("Intel Windows fallback unavailable: " + e.getMessage())
            );
        }
    }

    private void mergeIfSuccess(Map<String, Map<String, String>> merged, List<String> warnings, List<String> command) {
        try {
            CommandResult result = commandExecutor.execute(command);
            if (!result.isSuccess()) {
                warnings.add("Intel command failed: " + String.join(" ", command));
                return;
            }
            DetectorSupport.mergeDeviceMaps(merged, DetectorSupport.extractGpuMaps(result.stdout()));
        } catch (CommandExecutionException e) {
            warnings.add("Intel command unavailable: " + String.join(" ", command));
        }
    }

    private List<GpuDevice> buildDevices(String hostname, Instant scannedAt, Map<String, Map<String, String>> merged) {
        List<GpuDevice> devices = new ArrayList<>();
        for (Map<String, String> attributes : merged.values()) {
            String deviceId = DetectorSupport.firstValue(attributes, "device_id");
            String model = DetectorSupport.firstValue(attributes, "device_name");
            String uuid = DetectorSupport.firstValue(attributes, "uuid");
            String pciBusId = DetectorSupport.firstValue(attributes, "pci_bdf_address", "pci_bdf");
            Long totalMb = toMb(attributes, "memory_physical_size");
            Long usedMb = toMb(attributes, "gpu_memory_used");
            Long freeMb = totalMb != null && usedMb != null ? Math.max(totalMb - usedMb, 0L) : null;
            boolean integratedGraphics = isIntegratedIntelModel(model);
            boolean sharedSystemMemory = integratedGraphics || DetectorSupport.hasAnyKey(attributes, "shared_memory");
            Long sharedMemoryTotalMb = sharedSystemMemory
                    ? firstPresentMb(attributes,
                    "shared_memory_size",
                    "shared_memory_total",
                    "shared_system_memory",
                    "system_memory")
                    : null;

            devices.add(new GpuDevice(
                    hostname,
                    GpuVendor.INTEL,
                    deviceId,
                    model,
                    uuid,
                    pciBusId,
                    DetectorSupport.firstValue(attributes, "driver_version"),
                    totalMb,
                    freeMb,
                    resolveIntelGpuUtilization(attributes),
                    asDouble(attributes,
                            "gpu_memory_util",
                            "memory_utilization",
                            "memory_bandwidth_utilization",
                            "device_memory_utilization"),
                    asDouble(attributes, "gpu_core_temperature", "temperature"),
                    asDouble(attributes, "gpu_power", "power"),
                    null,
                    capabilityResolver.resolveEccEnabled(attributes),
                    capabilityResolver.resolveIntelInterconnect(attributes),
                    capabilityResolver.resolveHealthState(attributes),
                    integratedGraphics,
                    sharedSystemMemory,
                    sharedMemoryTotalMb,
                    false,
                    DetectorSupport.hasAnyKey(attributes, "tile", "vgpu"),
                    true,
                    true,
                    scannedAt
            ));
        }
        return devices;
    }

    private List<GpuDevice> parseWindowsDisplayAdapters(String hostname, Instant scannedAt, String json) {
        try {
            JsonNode root = DetectorSupport.OBJECT_MAPPER.readTree(json);
            Double hostGpuUtilization = readWindowsGpuEngineUtilization();
            Long nodeMemoryTotalMb = readWindowsPhysicalMemoryMb();
            List<GpuDevice> devices = new ArrayList<>();
            if (root == null || root.isNull()) {
                return devices;
            }
            if (root.isArray()) {
                for (JsonNode node : root) {
                    addWindowsDisplayAdapter(devices, hostname, scannedAt, node, devices.size(), hostGpuUtilization, nodeMemoryTotalMb);
                }
            } else if (root.isObject()) {
                addWindowsDisplayAdapter(devices, hostname, scannedAt, root, 0, hostGpuUtilization, nodeMemoryTotalMb);
            }
            return devices;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse Intel Windows fallback payload", e);
        }
    }

    private void addWindowsDisplayAdapter(
            List<GpuDevice> devices,
            String hostname,
            Instant scannedAt,
            JsonNode node,
            int index,
            Double hostGpuUtilization,
            Long nodeMemoryTotalMb
    ) {
        String model = text(node, "Name");
        if (!matchesIntelAcceleratorModel(model)) {
            return;
        }
        Long adapterRamMb = bytesToMb(text(node, "AdapterRAM"));
        boolean integratedGraphics = isIntegratedIntelModel(model);
        boolean sharedSystemMemory = integratedGraphics || adapterRamMb == null || adapterRamMb <= 512L;
        Long sharedMemoryTotalMb = sharedSystemMemory && nodeMemoryTotalMb != null
                ? Math.max((nodeMemoryTotalMb - 4096L) / WINDOWS_INTEGRATED_SHARED_MEMORY_DIVISOR, 0L)
                : null;
        devices.add(new GpuDevice(
                hostname,
                GpuVendor.INTEL,
                Integer.toString(index),
                model,
                DetectorSupport.blankToNull(text(node, "PNPDeviceID")),
                null,
                DetectorSupport.blankToNull(text(node, "DriverVersion")),
                adapterRamMb,
                null,
                hostGpuUtilization,
                null,
                null,
                null,
                null,
                null,
                InterconnectType.PCIE,
                HealthState.UNKNOWN,
                integratedGraphics,
                sharedSystemMemory,
                sharedMemoryTotalMb,
                false,
                false,
                true,
                true,
                scannedAt
        ));
    }

    private boolean matchesIntelAcceleratorModel(String model) {
        if (model == null) {
            return false;
        }
        String normalized = model.toLowerCase();
        if (!normalized.contains("intel")) {
            return false;
        }
        return normalized.contains("arc")
                || normalized.contains("flex")
                || normalized.contains("max")
                || normalized.contains("data center gpu")
                || normalized.contains("iris xe max");
    }

    private boolean isIntegratedIntelModel(String model) {
        if (model == null) {
            return false;
        }
        String normalized = model.toLowerCase();
        return normalized.contains("140v")
                || normalized.contains("130v")
                || normalized.contains("arc graphics")
                || normalized.contains("integrated")
                || normalized.contains("iris xe")
                || normalized.contains("uhd graphics");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long bytesToMb(String raw) {
        Long bytes = DetectorSupport.parseLongValue(raw);
        return bytes == null ? null : bytes / (1024 * 1024);
    }

    private Double readWindowsGpuEngineUtilization() {
        try {
            CommandResult result = commandExecutor.execute(List.of(
                    powershellCommand,
                    "-NoProfile",
                    "-Command",
                    "$samples = (Get-Counter '\\GPU Engine(*)\\Utilization Percentage').CounterSamples; " +
                            "$value = ($samples | Where-Object { $_.InstanceName -notmatch 'engtype_copy' } | " +
                            "Measure-Object -Property CookedValue -Sum).Sum; " +
                            "if ($null -eq $value) { '' } else { [math]::Round([double]$value, 2) }"
            ));
            if (!result.isSuccess()) {
                return null;
            }
            return DetectorSupport.parseDoubleValue(result.stdout());
        } catch (CommandExecutionException e) {
            return null;
        }
    }

    private Long readWindowsPhysicalMemoryMb() {
        try {
            CommandResult result = commandExecutor.execute(List.of(
                    powershellCommand,
                    "-NoProfile",
                    "-Command",
                    "[math]::Round(((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1MB), 0)"
            ));
            if (!result.isSuccess()) {
                return null;
            }
            return DetectorSupport.parseLongValue(result.stdout());
        } catch (CommandExecutionException e) {
            return null;
        }
    }

    private static Double resolveIntelGpuUtilization(Map<String, String> attributes) {
        return asDouble(attributes,
                "gpu_utilization",
                "gpu_util",
                "gpu_busy",
                "device_utilization",
                "compute_utilization",
                "compute_engine_utilization",
                "render_compute_utilization",
                "engine_utilization");
    }

    private static Long firstPresentMb(Map<String, String> attributes, String... fragments) {
        for (String fragment : fragments) {
            Long value = toMb(attributes, fragment);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static Double asDouble(Map<String, String> attributes, String... fragments) {
        return DetectorSupport.parseDoubleValue(DetectorSupport.firstValue(attributes, fragments));
    }

    private static Long toMb(Map<String, String> attributes, String... fragments) {
        String value = DetectorSupport.firstValue(attributes, fragments);
        Long raw = DetectorSupport.parseLongValue(value);
        if (raw == null) {
            return null;
        }
        if (value != null && value.toLowerCase().contains("mib")) {
            return raw;
        }
        return raw / 1024 / 1024;
    }
}
