package com.drewdrew1.infra.detector;

import com.drewdrew1.core.detector.DetectionResult;
import com.drewdrew1.core.detector.GpuDetector;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.core.model.HealthState;
import com.drewdrew1.core.model.InterconnectType;
import com.drewdrew1.core.service.CapabilityResolver;
import com.drewdrew1.infra.executor.CommandExecutionException;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Detects NVIDIA GPU inventory by parsing nvidia-smi output. */
public class NvidiaDetector implements GpuDetector {
    private static final String QUERY_FIELDS = String.join(",",
            "index",
            "name",
            "uuid",
            "pci.bus_id",
            "driver_version",
            "memory.total",
            "memory.free",
            "utilization.gpu",
            "utilization.memory",
            "temperature.gpu",
            "power.draw",
            "power.limit",
            "ecc.mode.current",
            "mig.mode.current"
    );

    private final CommandExecutor commandExecutor;
    private final CapabilityResolver capabilityResolver;
    private final String nvidiaSmiCommand;

    public NvidiaDetector(CommandExecutor commandExecutor, CapabilityResolver capabilityResolver, String nvidiaSmiCommand) {
        this.commandExecutor = commandExecutor;
        this.capabilityResolver = capabilityResolver;
        this.nvidiaSmiCommand = nvidiaSmiCommand;
    }

    @Override
    public DetectionResult detect(String hostname, Instant scannedAt) {
        try {
            CommandResult queryResult = commandExecutor.execute(List.of(
                    nvidiaSmiCommand,
                    "--query-gpu=" + QUERY_FIELDS,
                    "--format=csv,noheader,nounits"
            ));
            if (!queryResult.isSuccess()) {
                return new DetectionResult(
                        GpuVendor.NVIDIA,
                        Collections.emptyList(),
                        List.of("NVIDIA detector failed: " + queryResult.stderr().trim())
                );
            }

            String topologyMatrix = "";
            try {
                CommandResult topoResult = commandExecutor.execute(List.of(nvidiaSmiCommand, "topo", "-m"));
                if (topoResult.isSuccess()) {
                    topologyMatrix = topoResult.stdout();
                }
            } catch (CommandExecutionException ignored) {
                topologyMatrix = "";
            }

            return new DetectionResult(
                    GpuVendor.NVIDIA,
                    parseCsv(hostname, queryResult.stdout(), scannedAt, capabilityResolver, topologyMatrix),
                    List.of()
            );
        } catch (CommandExecutionException e) {
            return new DetectionResult(
                    GpuVendor.NVIDIA,
                    Collections.emptyList(),
                    List.of("NVIDIA detector unavailable: " + e.getMessage())
            );
        }
    }

    static List<GpuDevice> parseCsv(
            String hostname,
            String csv,
            Instant scannedAt,
            CapabilityResolver capabilityResolver,
            String topologyMatrix
    ) {
        List<GpuDevice> devices = new ArrayList<>();
        Map<String, InterconnectType> interconnectByIndex = capabilityResolver.resolveNvidiaInterconnects(topologyMatrix);

        for (String line : csv.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            List<String> columns = DetectorSupport.parseCsvLine(line);
            if (columns.size() < 14) {
                continue;
            }
            String deviceId = columns.get(0);
            String model = columns.get(1);
            String uuid = columns.get(2);
            String pciBusId = columns.get(3);
            String driverVersion = columns.get(4);
            Long vramTotalMb = DetectorSupport.parseLongValue(columns.get(5));
            Long vramFreeMb = DetectorSupport.parseLongValue(columns.get(6));
            Double gpuUtil = DetectorSupport.parseDoubleValue(columns.get(7));
            Double memUtil = DetectorSupport.parseDoubleValue(columns.get(8));
            Double temperature = DetectorSupport.parseDoubleValue(columns.get(9));
            Double powerUsage = DetectorSupport.parseDoubleValue(columns.get(10));
            Double powerLimit = DetectorSupport.parseDoubleValue(columns.get(11));
            Boolean eccEnabled = parseEnabled(columns.get(12));
            String migMode = columns.get(13);
            boolean supportsMig = capabilityResolver.supportsNvidiaMig(migMode);

            devices.add(new GpuDevice(
                    hostname,
                    GpuVendor.NVIDIA,
                    deviceId,
                    model,
                    uuid,
                    pciBusId,
                    driverVersion,
                    vramTotalMb,
                    vramFreeMb,
                    gpuUtil,
                    memUtil,
                    temperature,
                    powerUsage,
                    powerLimit,
                    eccEnabled,
                    interconnectByIndex.getOrDefault(deviceId, InterconnectType.PCIE),
                    HealthState.OK,
                    false,
                    false,
                    null,
                    supportsMig,
                    supportsMig,
                    true,
                    true,
                    scannedAt
            ));
        }
        return devices;
    }

    private static Boolean parseEnabled(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.contains("enabled")) {
            return true;
        }
        if (normalized.contains("disabled")) {
            return false;
        }
        return null;
    }
}
