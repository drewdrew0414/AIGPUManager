package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.AppContext;
import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.NodeInventory;
import com.drewdrew1.core.model.RemoteNodeRegistration;
import com.drewdrew1.core.model.ScanSummary;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/** Exposes node inventory, state management, and remote node registration commands. */
@Command(
        name = "node",
        mixinStandardHelpOptions = true,
        description = "Node and infrastructure operations",
        subcommands = {
                NodeCommand.ScanCommand.class,
                NodeCommand.ListCommand.class,
                NodeCommand.InfoCommand.class,
                NodeCommand.DrainCommand.class,
                NodeCommand.UndrainCommand.class,
                NodeCommand.TopCommand.class,
                NodeCommand.MaintenanceCommand.class,
                NodeCommand.LabelCommand.class,
                NodeCommand.RemoteCommand.class
        }
)
public class NodeCommand implements Runnable {
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @ParentCommand
    private GpuMgrCommand parent;

    @Spec
    private CommandSpec spec;

    AppContext context() {
        return parent.createContext();
    }

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    @Command(name = "scan", description = "Scan node hardware and persist inventory")
    static class ScanCommand implements Callable<Integer> {
        @ParentCommand
        private NodeCommand nodeCommand;

        @Option(names = "--all", description = "Reserved for cluster-wide discovery")
        private boolean all;

        @Option(names = "--ip", description = "Reserved for remote node scan")
        private String ip;

        @Option(names = "--ssh-user", description = "SSH username for remote scan")
        private String sshUser;

        @Option(names = "--force", description = "Force a fresh scan")
        private boolean force;

        @Option(names = "--discovery-depth", defaultValue = "1", description = "1=host, 2=topology-aware")
        private int discoveryDepth;

        @Override
        public Integer call() {
            CliSupport.requireRange(discoveryDepth, 1, 2, "discovery-depth");
            if (all) {
                int scanned = 0;
                ScanSummary local = nodeCommand.context().inventoryService().scanLocalNode();
                nodeCommand.context().inventoryRepository()
                        .putNodeAttribute(local.node().hostname(), "scan.discovery_depth", Integer.toString(discoveryDepth));
                nodeCommand.context().auditService().log("NODE_SCAN", actor(), local.node().hostname(), "local scan");
                nodeCommand.context().logService().info("node", "scan", "Scanned local node", local.node().hostname());
                scanned++;
                for (RemoteNodeRegistration registration : nodeCommand.context().inventoryRepository().listRemoteNodes()) {
                    if (!registration.enabled()) {
                        continue;
                    }
                    try {
                        ScanSummary remote = nodeCommand.context()
                                .remoteInventoryService(registration.address(), registration.sshUser())
                                .scanNode();
                        nodeCommand.context().inventoryRepository().putNodeAttribute(remote.node().hostname(), "remote.address", registration.address());
                        nodeCommand.context().inventoryRepository().putNodeAttribute(remote.node().hostname(), "remote.user", registration.sshUser());
                        nodeCommand.context().auditService().log("NODE_SCAN", actor(), remote.node().hostname(), "remote scan from " + registration.address());
                        nodeCommand.context().logService().info("node", "scan", "Scanned remote node", registration.address());
                        scanned++;
                    } catch (Exception e) {
                        nodeCommand.context().logService().warn("node", "scan", "Remote scan failed", registration.address() + ": " + e.getMessage());
                        System.out.printf("Remote scan failed for %s: %s%n", registration.address(), e.getMessage());
                    }
                }
                System.out.printf("Scanned %d node(s).%n", scanned);
                return 0;
            }

            if (ip != null) {
                CliSupport.requireNonBlank(ip, "ip");
                String user = sshUser == null || sshUser.isBlank() ? actor() : sshUser;
                ScanSummary remote = nodeCommand.context().remoteInventoryService(ip, user).scanNode();
                nodeCommand.context().inventoryRepository().putNodeAttribute(remote.node().hostname(), "remote.address", ip);
                nodeCommand.context().inventoryRepository().putNodeAttribute(remote.node().hostname(), "remote.user", user);
                nodeCommand.context().auditService().log("NODE_SCAN", actor(), remote.node().hostname(), "remote scan from " + ip);
                nodeCommand.context().logService().info("node", "scan", "Scanned remote node", ip);
                printSummary(remote);
                return 0;
            }

            ScanSummary summary = nodeCommand.context().inventoryService().scanLocalNode();
            if (force) {
                nodeCommand.context().inventoryRepository()
                        .putNodeAttribute(summary.node().hostname(), "scan.force", "true");
            }
            nodeCommand.context().inventoryRepository()
                    .putNodeAttribute(summary.node().hostname(), "scan.discovery_depth", Integer.toString(discoveryDepth));
            nodeCommand.context().auditService().log("NODE_SCAN", actor(), summary.node().hostname(), "local scan");
            nodeCommand.context().logService().info("node", "scan", "Scanned local node", summary.node().hostname());
            printSummary(summary);
            return 0;
        }

        private static void printSummary(ScanSummary summary) {
            System.out.printf(
                    "Scanned node %s: %d GPU(s) discovered across %d vendor(s).%n",
                    summary.node().hostname(),
                    summary.discoveredGpuCount(),
                    summary.discoveredVendors().size()
            );
            if (!summary.warnings().isEmpty()) {
                System.out.println("Warnings:");
                for (String warning : summary.warnings()) {
                    System.out.printf("- %s%n", warning);
                }
            }
        }
    }

    @Command(name = "list", description = "List nodes from inventory")
    static class ListCommand implements Callable<Integer> {
        @ParentCommand
        private NodeCommand nodeCommand;

        @Option(names = "--status", description = "Filter by status: active|drained|maintenance")
        private String status;

        @Option(names = "--vendor", description = "Filter nodes that contain vendor: NVIDIA|AMD|INTEL")
        private String vendor;

        @Option(names = "--label", description = "Filter by label key")
        private String labelKey;

        @Option(names = "--sort", description = "Sort by cpu|mem|gpu|disk", defaultValue = "cpu")
        private String sort;

        @Override
        public Integer call() {
            CliSupport.requireOneOf(sort, "sort", Set.of("cpu", "mem", "gpu", "disk"));
            List<NodeInventory> nodes = new ArrayList<>(nodeCommand.context().inventoryRepository().listNodes());
            Map<String, Map<String, String>> attributes = nodeCommand.context().inventoryRepository().listNodeAttributes();
            List<GpuDevice> gpus = nodeCommand.context().inventoryRepository().listGpus();

            List<String[]> rows = new ArrayList<>();
            nodes.sort(nodeComparator(sort, gpus));
            for (NodeInventory node : nodes) {
                Map<String, String> nodeAttrs = attributes.getOrDefault(node.hostname(), Map.of());
                String resolvedStatus = resolveStatus(nodeAttrs);
                if (status != null && !resolvedStatus.equalsIgnoreCase(status)) {
                    continue;
                }
                Set<String> vendors = vendorsForNode(node.hostname(), gpus);
                if (vendor != null && vendors.stream().noneMatch(v -> v.equalsIgnoreCase(vendor))) {
                    continue;
                }
                if (labelKey != null && !nodeAttrs.containsKey("label." + labelKey)) {
                    continue;
                }
                rows.add(new String[]{
                        node.hostname(),
                        resolvedStatus,
                        Integer.toString(node.cpuCores()),
                        Long.toString(node.memoryTotalMb()),
                        Integer.toString(countGpus(node.hostname(), gpus)),
                        vendors.isEmpty() ? "-" : String.join(",", vendors),
                        renderLabels(nodeAttrs),
                        TS_FORMATTER.format(node.lastScannedAt())
                });
            }

            if (rows.isEmpty()) {
                System.out.println("No nodes matched.");
                return 0;
            }

            System.out.println(AsciiTable.getTable(
                    new String[]{"Hostname", "Status", "CPU", "MemoryMB", "GPUs", "Vendors", "Labels", "LastScanned"},
                    rows.toArray(String[][]::new)
            ));
            return 0;
        }
    }

    @Command(name = "info", description = "Show node details")
    static class InfoCommand implements Callable<Integer> {
        @ParentCommand
        private NodeCommand nodeCommand;

        @Parameters(index = "0", arity = "0..1", paramLabel = "HOST")
        private String host;

        @Override
        public Integer call() {
            String hostname = host;
            if (hostname == null || hostname.isBlank()) {
                hostname = nodeCommand.context().systemInfoService().localNodeInventory().hostname();
            }
            Optional<NodeInventory> node = nodeCommand.context().inventoryRepository().findNode(hostname);
            CliSupport.require(node.isPresent(), "Node not found: " + hostname);

            Map<String, String> attrs = nodeCommand.context().inventoryRepository().getNodeAttributes(hostname);
            List<GpuDevice> gpus = nodeCommand.context().inventoryRepository().listGpusByNode(hostname);
            NodeInventory info = node.get();

            System.out.printf("Hostname: %s%n", info.hostname());
            System.out.printf("OS: %s (%s)%n", info.osName(), info.osArch());
            System.out.printf("CPU cores: %d%n", info.cpuCores());
            System.out.printf("Memory MB: %d%n", info.memoryTotalMb());
            System.out.printf("Status: %s%n", resolveStatus(attrs));
            System.out.printf("NUMA: %s%n", attrs.getOrDefault("system.numa", "unknown"));
            System.out.printf("RAM bandwidth: %s%n", attrs.getOrDefault("system.memory_bandwidth", "unknown"));
            System.out.printf("NIC/RDMA/InfiniBand: %s%n", attrs.getOrDefault("system.nic", "unknown"));
            System.out.printf("Driver deep analysis: partial (GPU driver version only)%n");
            System.out.printf("Last scanned: %s%n", TS_FORMATTER.format(info.lastScannedAt()));
            System.out.printf("Labels: %s%n", renderLabels(attrs));
            System.out.printf("Remote address: %s%n", attrs.getOrDefault("remote.address", "-"));
            System.out.printf("Remote user: %s%n", attrs.getOrDefault("remote.user", "-"));
            if (!gpus.isEmpty()) {
                System.out.println();
                System.out.println("GPU inventory:");
                List<String[]> rows = new ArrayList<>();
                for (GpuDevice gpu : gpus) {
                    rows.add(new String[]{
                            CliSupport.safe(gpu.deviceId()),
                            gpu.vendor().name(),
                            CliSupport.safe(gpu.model()),
                            CliSupport.safe(gpu.pciBusId()),
                            CliSupport.safe(gpu.driverVersion()),
                            gpu.interconnectType().name()
                    });
                }
                System.out.println(AsciiTable.getTable(
                        new String[]{"Id", "Vendor", "Model", "PCI", "Driver", "Link"},
                        rows.toArray(String[][]::new)
                ));
            }
            return 0;
        }
    }

    @Command(name = "drain", description = "Drain a node from scheduling")
    static class DrainCommand implements Callable<Integer> {
        @ParentCommand
        private NodeCommand nodeCommand;

        @Parameters(index = "0", paramLabel = "HOST")
        private String host;

        @Option(names = "--graceful")
        private boolean graceful;

        @Option(names = "--timeout", description = "Timeout in minutes", defaultValue = "30")
        private int timeoutMin;

        @Option(names = "--reason")
        private String reason = "unspecified";

        @Option(names = "--evict", description = "Attempt to move active allocations away from this node")
        private boolean evict;

        @Override
        public Integer call() {
            CliSupport.requireRange(timeoutMin, 1, 10_080, "timeout");
            ensureNodeExists(nodeCommand, host);
            nodeCommand.context().inventoryRepository().putNodeAttribute(host, "state.drained", "true");
            nodeCommand.context().inventoryRepository().putNodeAttribute(host, "state.drained.graceful", Boolean.toString(graceful));
            nodeCommand.context().inventoryRepository().putNodeAttribute(host, "state.drained.timeout_min", Integer.toString(timeoutMin));
            nodeCommand.context().inventoryRepository().putNodeAttribute(host, "state.drained.reason", reason);
            nodeCommand.context().inventoryRepository().putNodeAttribute(host, "state.drained.evict_requested", Boolean.toString(evict));
            nodeCommand.context().auditService().log("NODE_DRAIN", actor(), host, "graceful=" + graceful + ", reason=" + reason);
            System.out.printf("Node %s marked drained.%n", host);
            if (evict) {
                int moved = 0;
                int stuck = 0;
                List<String> failures = new ArrayList<>();
                for (var allocation : nodeCommand.context().allocationService().listAllocations()) {
                    if (allocation.devices().stream().noneMatch(device -> host.equalsIgnoreCase(device.nodeHostname()))) {
                        continue;
                    }
                    if (allocation.status() != com.drewdrew1.core.model.AllocationStatus.ACTIVE) {
                        continue;
                    }
                    try {
                        var replacement = nodeCommand.context().allocationService().moveAllocationAwayFromNode(allocation.id(), host);
                        nodeCommand.context().auditService().log(
                                "NODE_EVICT_MOVE",
                                actor(),
                                allocation.id(),
                                "host=" + host + ", replacement=" + replacement.id()
                        );
                        moved++;
                    } catch (Exception e) {
                        failures.add(allocation.id() + ": " + e.getMessage());
                        stuck++;
                    }
                }
                System.out.printf("Eviction summary: moved=%d, stuck=%d%n", moved, stuck);
                for (String failure : failures) {
                    System.out.println("- " + failure);
                }
            }
            return 0;
        }
    }

    @Command(name = "undrain", description = "Return a node to active scheduling")
    static class UndrainCommand implements Callable<Integer> {
        @ParentCommand
        private NodeCommand nodeCommand;

        @Parameters(index = "0", paramLabel = "HOST")
        private String host;

        @Override
        public Integer call() {
            ensureNodeExists(nodeCommand, host);
            nodeCommand.context().inventoryRepository().removeNodeAttribute(host, "state.drained");
            nodeCommand.context().inventoryRepository().removeNodeAttribute(host, "state.drained.graceful");
            nodeCommand.context().inventoryRepository().removeNodeAttribute(host, "state.drained.timeout_min");
            nodeCommand.context().inventoryRepository().removeNodeAttribute(host, "state.drained.reason");
            nodeCommand.context().inventoryRepository().removeNodeAttribute(host, "state.drained.evict_requested");
            nodeCommand.context().auditService().log("NODE_UNDRAIN", actor(), host, "node returned to active");
            System.out.printf("Node %s returned to active scheduling.%n", host);
            return 0;
        }
    }

    @Command(name = "top", description = "Show node GPU aggregates")
    static class TopCommand implements Callable<Integer> {
        @ParentCommand
        private NodeCommand nodeCommand;

        @Option(names = "--live", description = "Refresh every second until interrupted")
        private boolean live;

        @Option(names = "--metric", defaultValue = "util", description = "power|temp|util")
        private String metric;

        @Override
        public Integer call() throws Exception {
            CliSupport.requireOneOf(metric, "metric", Set.of("power", "temp", "util"));
            do {
                printTop();
                if (!live) {
                    break;
                }
                Thread.sleep(1000L);
            } while (!Thread.currentThread().isInterrupted());
            return 0;
        }

        private void printTop() {
            List<NodeInventory> nodes = nodeCommand.context().inventoryRepository().listNodes();
            List<GpuDevice> gpus = nodeCommand.context().inventoryRepository().listGpus();
            List<String[]> rows = new ArrayList<>();
            for (NodeInventory node : nodes) {
                List<GpuDevice> nodeGpus = gpus.stream().filter(g -> node.hostname().equalsIgnoreCase(g.nodeHostname())).toList();
                double avg = switch (metric) {
                    case "power" -> average(nodeGpus, "power");
                    case "temp" -> average(nodeGpus, "temp");
                    default -> average(nodeGpus, "util");
                };
                rows.add(new String[]{
                        node.hostname(),
                        Integer.toString(nodeGpus.size()),
                        String.format(Locale.ROOT, "%.1f", avg),
                        metric
                });
            }
            System.out.println(AsciiTable.getTable(new String[]{"Node", "GPUs", "Avg", "Metric"}, rows.toArray(String[][]::new)));
        }

        private double average(List<GpuDevice> gpus, String kind) {
            double sum = 0.0;
            int count = 0;
            for (GpuDevice gpu : gpus) {
                Double value = switch (kind) {
                    case "power" -> gpu.powerUsageW();
                    case "temp" -> gpu.temperatureC();
                    default -> gpu.utilizationGpu();
                };
                if (value != null) {
                    sum += value;
                    count++;
                }
            }
            return count == 0 ? 0.0 : sum / count;
        }
    }

    @Command(name = "maintenance", description = "Toggle maintenance mode")
    static class MaintenanceCommand implements Callable<Integer> {
        @ParentCommand
        private NodeCommand nodeCommand;

        @Parameters(index = "0", paramLabel = "HOST")
        private String host;

        @Option(names = "--on")
        private boolean on;

        @Option(names = "--off")
        private boolean off;

        @Option(names = "--reason", defaultValue = "maintenance")
        private String reason;

        @Override
        public Integer call() {
            CliSupport.require(!(on && off), "Choose either --on or --off");
            ensureNodeExists(nodeCommand, host);
            boolean enabled = !off;
            if (enabled) {
                nodeCommand.context().inventoryRepository().putNodeAttribute(host, "state.maintenance", "true");
                nodeCommand.context().inventoryRepository().putNodeAttribute(host, "state.maintenance.reason", reason);
            } else {
                nodeCommand.context().inventoryRepository().removeNodeAttribute(host, "state.maintenance");
                nodeCommand.context().inventoryRepository().removeNodeAttribute(host, "state.maintenance.reason");
            }
            nodeCommand.context().auditService().log("NODE_MAINTENANCE", actor(), host, "enabled=" + enabled + ", reason=" + reason);
            System.out.printf("Node %s maintenance mode: %s%n", host, enabled ? "enabled" : "disabled");
            return 0;
        }
    }

    @Command(name = "label", description = "Manage scheduling labels")
    static class LabelCommand implements Callable<Integer> {
        @ParentCommand
        private NodeCommand nodeCommand;

        @Parameters(index = "0", paramLabel = "HOST")
        private String host;

        @Option(names = "--set", split = ",", description = "One or more key=value labels")
        private List<String> setPairs = List.of();

        @Option(names = "--remove", split = ",", description = "One or more label keys")
        private List<String> removeKeys = List.of();

        @Option(names = "--show")
        private boolean show;

        @Override
        public Integer call() {
            ensureNodeExists(nodeCommand, host);
            int selected = (show ? 1 : 0) + (!setPairs.isEmpty() ? 1 : 0) + (!removeKeys.isEmpty() ? 1 : 0);
            CliSupport.require(selected == 1, "Choose exactly one of --set, --remove, --show");

            if (show) {
                Map<String, String> attrs = nodeCommand.context().inventoryRepository().getNodeAttributes(host);
                System.out.printf("Labels on %s: %s%n", host, renderLabels(attrs));
                return 0;
            }
            if (!setPairs.isEmpty()) {
                Map<String, String> labels = CliSupport.parseLabels(setPairs);
                for (Map.Entry<String, String> entry : labels.entrySet()) {
                    nodeCommand.context().inventoryRepository()
                            .putNodeAttribute(host, "label." + entry.getKey(), entry.getValue());
                }
                nodeCommand.context().auditService().log("NODE_LABEL_SET", actor(), host, String.join(",", setPairs));
                System.out.printf("Updated labels on %s.%n", host);
                return 0;
            }
            for (String removeKey : removeKeys) {
                nodeCommand.context().inventoryRepository().removeNodeAttribute(host, "label." + removeKey);
            }
            nodeCommand.context().auditService().log("NODE_LABEL_REMOVE", actor(), host, String.join(",", removeKeys));
            System.out.printf("Removed labels on %s.%n", host);
            return 0;
        }
    }

    @Command(
            name = "remote",
            description = "Manage remote node registrations",
            subcommands = {
                    RemoteCommand.AddCommand.class,
                    RemoteCommand.ListCommand.class,
                    RemoteCommand.RemoveCommand.class
            }
    )
    static class RemoteCommand implements Runnable {
        @ParentCommand
        private NodeCommand nodeCommand;

        @Spec
        private CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(System.out);
        }

        @Command(name = "add", description = "Register a remote node")
        static class AddCommand implements Callable<Integer> {
            @ParentCommand
            private RemoteCommand remoteCommand;

            @Option(names = "--ip", required = true)
            private String ip;

            @Option(names = "--ssh-user", required = true)
            private String sshUser;

            @Option(names = "--alias")
            private String alias;

            @Override
            public Integer call() {
                CliSupport.requireNonBlank(ip, "ip");
                CliSupport.requireNonBlank(sshUser, "ssh-user");
                remoteCommand.nodeCommand.context().inventoryRepository()
                        .upsertRemoteNode(new RemoteNodeRegistration(ip, sshUser, alias, true, java.time.Instant.now()));
                remoteCommand.nodeCommand.context().auditService().log("REMOTE_NODE_ADD", actor(), ip, "user=" + sshUser);
                remoteCommand.nodeCommand.context().logService().info("node", "remote", "Registered remote node", ip);
                System.out.printf("Registered remote node %s%n", ip);
                return 0;
            }
        }

        @Command(name = "list", description = "List remote nodes")
        static class ListCommand implements Callable<Integer> {
            @ParentCommand
            private RemoteCommand remoteCommand;

            @Override
            public Integer call() {
                List<RemoteNodeRegistration> nodes = remoteCommand.nodeCommand.context().inventoryRepository().listRemoteNodes();
                if (nodes.isEmpty()) {
                    System.out.println("No remote nodes registered.");
                    return 0;
                }
                List<String[]> rows = new ArrayList<>();
                for (RemoteNodeRegistration node : nodes) {
                    rows.add(new String[]{
                            node.address(),
                            node.sshUser(),
                            node.alias() == null ? "-" : node.alias(),
                            Boolean.toString(node.enabled()),
                            TS_FORMATTER.format(node.createdAt())
                    });
                }
                System.out.println(AsciiTable.getTable(
                        new String[]{"Address", "User", "Alias", "Enabled", "Created"},
                        rows.toArray(String[][]::new)
                ));
                return 0;
            }
        }

        @Command(name = "remove", description = "Remove a remote node")
        static class RemoveCommand implements Callable<Integer> {
            @ParentCommand
            private RemoteCommand remoteCommand;

            @Option(names = "--ip", required = true)
            private String ip;

            @Override
            public Integer call() {
                CliSupport.requireNonBlank(ip, "ip");
                remoteCommand.nodeCommand.context().inventoryRepository().removeRemoteNode(ip);
                remoteCommand.nodeCommand.context().auditService().log("REMOTE_NODE_REMOVE", actor(), ip, "removed registration");
                remoteCommand.nodeCommand.context().logService().info("node", "remote", "Removed remote node", ip);
                System.out.printf("Removed remote node %s%n", ip);
                return 0;
            }
        }
    }

    private static String actor() {
        return System.getProperty("user.name", "unknown");
    }

    private static void ensureNodeExists(NodeCommand command, String host) {
        CliSupport.requireNonBlank(host, "host");
        CliSupport.require(command.context().inventoryRepository().findNode(host).isPresent(), "Node not found: " + host);
    }

    private static String resolveStatus(Map<String, String> attrs) {
        if ("true".equalsIgnoreCase(attrs.get("state.maintenance"))) {
            return "maintenance";
        }
        if ("true".equalsIgnoreCase(attrs.get("state.drained"))) {
            return "drained";
        }
        return "active";
    }

    private static Set<String> vendorsForNode(String hostname, List<GpuDevice> gpus) {
        Set<String> vendors = new TreeSet<>();
        for (GpuDevice gpu : gpus) {
            if (hostname.equalsIgnoreCase(gpu.nodeHostname())) {
                vendors.add(gpu.vendor().name());
            }
        }
        return vendors;
    }

    private static int countGpus(String hostname, List<GpuDevice> gpus) {
        int count = 0;
        for (GpuDevice gpu : gpus) {
            if (hostname.equalsIgnoreCase(gpu.nodeHostname())) {
                count++;
            }
        }
        return count;
    }

    private static String renderLabels(Map<String, String> attrs) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            if (entry.getKey().startsWith("label.")) {
                labels.put(entry.getKey().substring("label.".length()), entry.getValue());
            }
        }
        if (labels.isEmpty()) {
            return "-";
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            pairs.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join(",", pairs);
    }

    private static Comparator<NodeInventory> nodeComparator(String sort, List<GpuDevice> gpus) {
        return switch (sort.toLowerCase(Locale.ROOT)) {
            case "mem" -> Comparator.comparingLong(NodeInventory::memoryTotalMb).reversed().thenComparing(NodeInventory::hostname);
            case "gpu" -> Comparator.<NodeInventory>comparingInt(node -> countGpus(node.hostname(), gpus)).reversed()
                    .thenComparing(NodeInventory::hostname);
            case "disk" -> Comparator.comparing(NodeInventory::hostname);
            default -> Comparator.comparingInt(NodeInventory::cpuCores).reversed().thenComparing(NodeInventory::hostname);
        };
    }
}
