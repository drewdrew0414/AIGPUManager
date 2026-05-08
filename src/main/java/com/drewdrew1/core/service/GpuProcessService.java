package com.drewdrew1.core.service;

import com.drewdrew1.core.config.GpumConfig;
import com.drewdrew1.core.model.AllocationDevice;
import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.infra.executor.CommandExecutionException;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Locates GPU-bound local processes and terminates them conservatively when requested. */
public class GpuProcessService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CommandExecutor commandExecutor;
    private final GpumConfig config;
    private final SystemInfoService systemInfoService;

    public GpuProcessService(CommandExecutor commandExecutor, GpumConfig config, SystemInfoService systemInfoService) {
        this.commandExecutor = commandExecutor;
        this.config = config;
        this.systemInfoService = systemInfoService;
    }

    public CleanupResult cleanupAllocationProcesses(AllocationRecord allocation, boolean force) {
        String localHost = systemInfoService.localNodeInventory().hostname();
        List<AllocationDevice> localDevices = allocation.devices().stream()
                .filter(device -> localHost.equalsIgnoreCase(device.nodeHostname()))
                .toList();
        if (localDevices.isEmpty()) {
            return new CleanupResult(List.of(), List.of(), List.of("No local GPU devices were assigned to this allocation."));
        }

        Map<Long, ProcessMatch> matches = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (GpuVendor vendor : vendorSet(localDevices)) {
            try {
                for (ProcessMatch match : listProcesses(vendor, localDevices)) {
                    matches.putIfAbsent(match.pid(), match);
                }
            } catch (Exception e) {
                warnings.add(vendor.name() + " process discovery failed: " + e.getMessage());
            }
        }

        if (matches.isEmpty()) {
            return new CleanupResult(List.of(), List.of(), warnings);
        }

        CleanupResult termination = terminateMatches(matches.values().stream()
                .sorted(Comparator.comparingLong(ProcessMatch::pid))
                .toList(), force);
        return new CleanupResult(termination.killed(), termination.skipped(), warnings);
    }

    public List<ProcessMatch> listProcessesForGpu(GpuDevice gpu) {
        List<AllocationDevice> devices = List.of(new AllocationDevice(
                gpu.nodeHostname(),
                gpu.vendor(),
                gpu.deviceId(),
                gpu.uuid(),
                gpu.model(),
                gpu.pciBusId()
        ));
        return listProcesses(gpu.vendor(), devices);
    }

    public CleanupResult cleanupGpuProcesses(GpuDevice gpu, boolean force) {
        Map<Long, ProcessMatch> matches = new LinkedHashMap<>();
        for (ProcessMatch match : listProcessesForGpu(gpu)) {
            matches.put(match.pid(), match);
        }
        if (matches.isEmpty()) {
            return new CleanupResult(List.of(), List.of(), List.of());
        }
        return terminateMatches(matches.values().stream().sorted(Comparator.comparingLong(ProcessMatch::pid)).toList(), force);
    }

    private List<ProcessMatch> listProcesses(GpuVendor vendor, List<AllocationDevice> devices) {
        return switch (vendor) {
            case NVIDIA -> listNvidiaProcesses(devices);
            case AMD -> listAmdProcesses(devices);
            case INTEL -> listIntelProcesses(devices);
        };
    }

    private List<ProcessMatch> listNvidiaProcesses(List<AllocationDevice> devices) {
        Set<String> selectors = selectorSet(devices);
        try {
            CommandResult result = commandExecutor.execute(List.of(
                    config.getTools().getNvidiaSmi(),
                    "--query-compute-apps=pid,gpu_uuid,process_name",
                    "--format=csv,noheader,nounits"
            ));
            if (!result.isSuccess()) {
                return List.of();
            }
            Map<Long, ProcessMatch> matches = new LinkedHashMap<>();
            for (String line : result.stdout().split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] columns = line.split(",", 3);
                if (columns.length < 2) {
                    continue;
                }
                long pid = parseLong(columns[0]);
                String uuid = columns[1].trim();
                if (!selectors.contains(normalizeSelector(uuid))) {
                    continue;
                }
                String command = columns.length >= 3 ? columns[2].trim() : "unknown";
                matches.computeIfAbsent(pid, ignored -> new ProcessMatch(pid, command, GpuVendor.NVIDIA, new LinkedHashSet<>()))
                        .gpuSelectors().add(uuid);
            }
            return new ArrayList<>(matches.values());
        } catch (CommandExecutionException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private List<ProcessMatch> listAmdProcesses(List<AllocationDevice> devices) {
        Set<String> deviceIds = new LinkedHashSet<>();
        for (AllocationDevice device : devices) {
            if (device.deviceId() != null && !device.deviceId().isBlank()) {
                deviceIds.add(device.deviceId().trim());
            }
        }
        try {
            CommandResult result = commandExecutor.execute(List.of(
                    config.getTools().getAmdSmi(),
                    "process",
                    "--csv",
                    "-G"
            ));
            if (!result.isSuccess()) {
                return List.of();
            }
            Map<Long, ProcessMatch> matches = new LinkedHashMap<>();
            for (String line : result.stdout().split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isBlank() || trimmed.toLowerCase(Locale.ROOT).startsWith("gpu")) {
                    continue;
                }
                String[] columns = trimmed.split("\\s*,\\s*");
                if (columns.length < 2) {
                    continue;
                }
                String gpuId = columns[0].trim();
                if (!deviceIds.contains(gpuId)) {
                    continue;
                }
                long pid = parseLong(columns[1]);
                String command = columns.length >= 3 ? columns[2].trim() : "unknown";
                matches.computeIfAbsent(pid, ignored -> new ProcessMatch(pid, command, GpuVendor.AMD, new LinkedHashSet<>()))
                        .gpuSelectors().add(gpuId);
            }
            return new ArrayList<>(matches.values());
        } catch (CommandExecutionException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private List<ProcessMatch> listIntelProcesses(List<AllocationDevice> devices) {
        Set<String> deviceIds = new LinkedHashSet<>();
        for (AllocationDevice device : devices) {
            if (device.deviceId() != null && !device.deviceId().isBlank()) {
                deviceIds.add(device.deviceId().trim());
            }
        }
        try {
            CommandResult result = commandExecutor.execute(List.of(
                    config.getTools().getXpuSmi(),
                    "ps",
                    "-j"
            ));
            if (!result.isSuccess()) {
                return List.of();
            }
            JsonNode root = OBJECT_MAPPER.readTree(result.stdout());
            List<JsonNode> nodes = new ArrayList<>();
            collectProcessNodes(root, nodes);
            Map<Long, ProcessMatch> matches = new LinkedHashMap<>();
            for (JsonNode node : nodes) {
                String deviceId = text(node, "device_id", "deviceId", "DeviceID");
                if (deviceId == null || !deviceIds.contains(deviceId.trim())) {
                    continue;
                }
                long pid = parseLong(text(node, "pid", "PID"));
                String command = text(node, "command", "Command", "name");
                matches.computeIfAbsent(pid, ignored -> new ProcessMatch(pid, command == null ? "unknown" : command, GpuVendor.INTEL, new LinkedHashSet<>()))
                        .gpuSelectors().add(deviceId);
            }
            return new ArrayList<>(matches.values());
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private boolean terminateTree(ProcessHandle handle, boolean force) {
        List<ProcessHandle> descendants = handle.descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .toList();
        for (ProcessHandle descendant : descendants) {
            descendant.destroy();
        }
        handle.destroy();
        if (waitForExit(handle, Duration.ofSeconds(5))) {
            return true;
        }
        if (force) {
            for (ProcessHandle descendant : descendants) {
                descendant.destroyForcibly();
            }
            handle.destroyForcibly();
            return waitForExit(handle, Duration.ofSeconds(5));
        }
        return false;
    }

    private boolean waitForExit(ProcessHandle handle, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (handle.isAlive() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return !handle.isAlive();
            }
        }
        return !handle.isAlive();
    }

    private Set<GpuVendor> vendorSet(List<AllocationDevice> devices) {
        Set<GpuVendor> vendors = new LinkedHashSet<>();
        for (AllocationDevice device : devices) {
            vendors.add(device.vendor());
        }
        return vendors;
    }

    private Set<String> selectorSet(List<AllocationDevice> devices) {
        Set<String> selectors = new LinkedHashSet<>();
        for (AllocationDevice device : devices) {
            if (device.uuid() != null && !device.uuid().isBlank()) {
                selectors.add(normalizeSelector(device.uuid()));
            }
            if (device.deviceId() != null && !device.deviceId().isBlank()) {
                selectors.add(normalizeSelector(device.deviceId()));
            }
        }
        return selectors;
    }

    private String normalizeSelector(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private long parseLong(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Missing numeric value");
        }
        String normalized = raw.replaceAll("[^0-9-]", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Missing numeric value");
        }
        return Long.parseLong(normalized);
    }

    private void collectProcessNodes(JsonNode node, List<JsonNode> results) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectProcessNodes(child, results);
            }
            return;
        }
        if (node.isObject()) {
            if (text(node, "pid", "PID") != null && text(node, "device_id", "deviceId", "DeviceID") != null) {
                results.add(node);
            }
            node.fields().forEachRemaining(entry -> collectProcessNodes(entry.getValue(), results));
        }
    }

    private String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode child = node.get(key);
            if (child != null && !child.isNull()) {
                return child.asText();
            }
        }
        return null;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private CleanupResult terminateMatches(List<ProcessMatch> matches, boolean force) {
        long currentPid = ProcessHandle.current().pid();
        List<KilledProcess> killed = new ArrayList<>();
        List<SkippedProcess> skipped = new ArrayList<>();
        for (ProcessMatch match : matches) {
            if (match.pid() == currentPid) {
                skipped.add(new SkippedProcess(match.pid(), match.command(), "refusing to terminate the gpum process itself"));
                continue;
            }
            if (match.pid() <= (isWindows() ? 4L : 1L)) {
                skipped.add(new SkippedProcess(match.pid(), match.command(), "refusing to terminate a system-reserved process"));
                continue;
            }
            Optional<ProcessHandle> handle = ProcessHandle.of(match.pid());
            if (handle.isEmpty()) {
                skipped.add(new SkippedProcess(match.pid(), match.command(), "process no longer exists"));
                continue;
            }
            Optional<String> processUser = handle.get().info().user();
            if (!force && processUser.isPresent()) {
                String currentUser = System.getProperty("user.name", "unknown");
                if (!processUser.get().equalsIgnoreCase(currentUser)) {
                    skipped.add(new SkippedProcess(match.pid(), match.command(), "belongs to another OS user"));
                    continue;
                }
            }
            if (terminateTree(handle.get(), force)) {
                killed.add(new KilledProcess(match.pid(), match.command(), match.vendor().name(), match.gpuSelectors()));
            } else {
                skipped.add(new SkippedProcess(match.pid(), match.command(), "did not exit after graceful termination"));
            }
        }
        return new CleanupResult(killed, skipped, List.of());
    }

    /** Describes one local GPU process that was successfully terminated. */
    public record KilledProcess(long pid, String command, String vendor, Set<String> gpuSelectors) {
    }

    /** Describes one local GPU process that was intentionally not terminated. */
    public record SkippedProcess(long pid, String command, String reason) {
    }

    /** Returns the local cleanup outcome for one allocation release attempt. */
    public record CleanupResult(List<KilledProcess> killed, List<SkippedProcess> skipped, List<String> warnings) {
    }

    /** Represents one vendor-reported GPU-bound process before any termination attempt. */
    public record ProcessMatch(long pid, String command, GpuVendor vendor, Set<String> gpuSelectors) {
    }
}
