package com.drewdrew1.infra.detector;

import com.drewdrew1.core.detector.DetectionResult;
import com.drewdrew1.core.detector.GpuDetector;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuVendor;
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

/** Detects Intel GPU inventory by parsing xpu-smi style output. */
public class IntelDetector implements GpuDetector {
    private final CommandExecutor commandExecutor;
    private final CapabilityResolver capabilityResolver;

    public IntelDetector(CommandExecutor commandExecutor, CapabilityResolver capabilityResolver) {
        this.commandExecutor = commandExecutor;
        this.capabilityResolver = capabilityResolver;
    }

    @Override
    public DetectionResult detect(String hostname, Instant scannedAt) {
        try {
            CommandResult discovery = commandExecutor.execute(List.of("xpu-smi", "discovery", "-j"));
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
                mergeIfSuccess(merged, warnings, List.of("xpu-smi", "discovery", "-d", selector, "-j"));
                mergeIfSuccess(merged, warnings, List.of("xpu-smi", "stats", "-d", selector, "-j"));
                mergeIfSuccess(merged, warnings, List.of("xpu-smi", "health", "-d", selector, "-j"));
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
                    asDouble(attributes, "gpu_utilization"),
                    asDouble(attributes, "gpu_memory_util"),
                    asDouble(attributes, "gpu_core_temperature", "temperature"),
                    asDouble(attributes, "gpu_power", "power"),
                    null,
                    capabilityResolver.resolveEccEnabled(attributes),
                    capabilityResolver.resolveIntelInterconnect(attributes),
                    capabilityResolver.resolveHealthState(attributes),
                    false,
                    DetectorSupport.hasAnyKey(attributes, "tile", "vgpu"),
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
