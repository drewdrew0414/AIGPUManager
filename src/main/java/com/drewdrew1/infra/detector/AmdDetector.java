package com.drewdrew1.infra.detector;

import com.drewdrew1.core.detector.DetectionResult;
import com.drewdrew1.core.detector.GpuDetector;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.core.model.InterconnectType;
import com.drewdrew1.core.service.CapabilityResolver;
import com.drewdrew1.infra.executor.CommandExecutionException;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detects AMD GPU inventory by parsing amd-smi or rocm-smi output. */
public class AmdDetector implements GpuDetector {
    private final CommandExecutor commandExecutor;
    private final CapabilityResolver capabilityResolver;
    private final String amdSmiCommand;
    private final String rocmSmiCommand;

    public AmdDetector(
            CommandExecutor commandExecutor,
            CapabilityResolver capabilityResolver,
            String amdSmiCommand,
            String rocmSmiCommand
    ) {
        this.commandExecutor = commandExecutor;
        this.capabilityResolver = capabilityResolver;
        this.amdSmiCommand = amdSmiCommand;
        this.rocmSmiCommand = rocmSmiCommand;
    }

    @Override
    public DetectionResult detect(String hostname, Instant scannedAt) {
        List<String> warnings = new ArrayList<>();
        Map<String, Map<String, String>> merged = new LinkedHashMap<>();

        if (!runAmdSmiCommands(merged, warnings)) {
            runRocmSmiFallback(merged, warnings);
        }

        if (merged.isEmpty()) {
            return new DetectionResult(GpuVendor.AMD, Collections.emptyList(), warnings);
        }
        return new DetectionResult(GpuVendor.AMD, buildDevices(hostname, scannedAt, merged), warnings);
    }

    private boolean runAmdSmiCommands(Map<String, Map<String, String>> merged, List<String> warnings) {
        List<List<String>> commands = List.of(
                List.of(amdSmiCommand, "list", "--json"),
                List.of(amdSmiCommand, "static", "--json"),
                List.of(amdSmiCommand, "metric", "--json"),
                List.of(amdSmiCommand, "xgmi", "--json")
        );

        boolean anySuccess = false;
        for (List<String> command : commands) {
            try {
                CommandResult result = commandExecutor.execute(command);
                if (!result.isSuccess()) {
                    warnings.add("AMD command failed: " + String.join(" ", command));
                    continue;
                }
                DetectorSupport.mergeDeviceMaps(merged, DetectorSupport.extractGpuMaps(result.stdout()));
                anySuccess = true;
            } catch (CommandExecutionException e) {
                warnings.add("AMD command unavailable: " + String.join(" ", command));
                return false;
            }
        }
        return anySuccess;
    }

    private void runRocmSmiFallback(Map<String, Map<String, String>> merged, List<String> warnings) {
        try {
            CommandResult result = commandExecutor.execute(List.of(
                    rocmSmiCommand,
                    "--showproductname",
                    "--showuniqueid",
                    "--showbus",
                    "--showmeminfo",
                    "vram",
                    "--showuse",
                    "--showtemp",
                    "--showpower",
                    "--showtopo",
                    "--json"
            ));
            if (!result.isSuccess()) {
                warnings.add("ROCm fallback failed: " + result.stderr().trim());
                return;
            }
            DetectorSupport.mergeDeviceMaps(merged, DetectorSupport.extractGpuMaps(result.stdout()));
        } catch (CommandExecutionException e) {
            warnings.add("ROCm fallback unavailable: " + e.getMessage());
        }
    }

    private List<GpuDevice> buildDevices(String hostname, Instant scannedAt, Map<String, Map<String, String>> merged) {
        List<GpuDevice> devices = new ArrayList<>();
        for (Map<String, String> attributes : merged.values()) {
            String model = DetectorSupport.firstValue(attributes,
                    "product_name",
                    "card_series",
                    "market_name",
                    "device_name",
                    "card_model");
            String uuid = DetectorSupport.firstValue(attributes, "uuid", "unique_id");
            String pciBusId = DetectorSupport.firstValue(attributes, "pci_bdf", "pci_bus", "bdf");
            String deviceId = DetectorSupport.firstValue(attributes, "gpu_id", "device_id", "card");
            Long totalMb = toMb(attributes, "memory_physical_size", "vram_total_memory_b", "vram_total", "memory_total");
            Long usedMb = toMb(attributes, "vram_used_memory_b", "vram_used", "memory_used");
            Long freeMb = DetectorSupport.firstValue(attributes, "memory_free", "vram_free") != null
                    ? toMb(attributes, "memory_free", "vram_free")
                    : subtract(totalMb, usedMb);

            devices.add(new GpuDevice(
                    hostname,
                    GpuVendor.AMD,
                    deviceId,
                    model,
                    uuid,
                    pciBusId,
                    DetectorSupport.firstValue(attributes, "driver_version"),
                    totalMb,
                    freeMb,
                    asDouble(attributes, "gpu_use", "utilization", "gfx_activity"),
                    asDouble(attributes, "memory_use", "memory_utilization"),
                    asDouble(attributes, "temperature", "edge_temp", "junction_temp"),
                    asDouble(attributes, "power", "average_socket_power"),
                    asDouble(attributes, "power_cap", "max_power"),
                    capabilityResolver.resolveEccEnabled(attributes),
                    capabilityResolver.resolveAmdInterconnect(attributes),
                    capabilityResolver.resolveHealthState(attributes),
                    false,
                    false,
                    null,
                    false,
                    DetectorSupport.hasAnyKey(attributes, "partition"),
                    true,
                    true,
                    scannedAt
            ));
        }
        return devices;
    }

    private static Double asDouble(Map<String, String> attributes, String... fragments) {
        return DetectorSupport.parseDoubleValue(DetectorSupport.firstValue(attributes, fragments));
    }

    private static Long toMb(Map<String, String> attributes, String... fragments) {
        String source = DetectorSupport.firstValue(attributes, fragments);
        Long raw = DetectorSupport.parseLongValue(source);
        if (raw == null) {
            return null;
        }
        if (source != null && source.toLowerCase().contains("mib")) {
            return raw;
        }
        if (source != null && source.toLowerCase().contains("mb")) {
            return raw;
        }
        if ((source != null && source.toLowerCase().contains("b")) || raw > 10_000_000L) {
            return raw / (1024 * 1024);
        }
        return raw;
    }

    private static Long subtract(Long totalMb, Long usedMb) {
        if (totalMb == null || usedMb == null) {
            return null;
        }
        return Math.max(totalMb - usedMb, 0L);
    }
}
