package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.AppContext;
import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.AllocationAffinity;
import com.drewdrew1.core.model.AllocationDecision;
import com.drewdrew1.core.model.AllocationDevice;
import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.AllocationRequest;
import com.drewdrew1.core.model.AllocationStatus;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

/** Exposes allocation lifecycle commands such as request, list, and release. */
@Command(
        name = "alloc",
        mixinStandardHelpOptions = true,
        description = "Allocation and lease operations",
        subcommands = {
                AllocCommand.RequestCommand.class,
                AllocCommand.ListCommand.class,
                AllocCommand.InfoCommand.class,
                AllocCommand.ExtendCommand.class,
                AllocCommand.ReleaseCommand.class,
                AllocCommand.ReapCommand.class,
                AllocCommand.MoveCommand.class
        }
)
public class AllocCommand implements Runnable {
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

    @Command(name = "request", description = "Request GPU resources")
    static class RequestCommand implements Callable<Integer> {
        @ParentCommand
        private AllocCommand allocCommand;

        @Option(names = "--gpus")
        private Integer gpus;
        @Option(names = "--model")
        private String model;
        @Option(names = "--vram")
        private Long vramMb;
        @Option(names = "--exclusive")
        private boolean exclusive;
        @Option(names = "--hours")
        private Integer hours;
        @Option(names = "--priority")
        private Integer priority;
        @Option(names = "--preemptible")
        private boolean preemptible;
        @Option(names = "--affinity", defaultValue = "packed")
        private String affinity;
        @Option(names = "--label-selector")
        private String labelSelector;
        @Option(names = "--dry-run")
        private boolean dryRun;

        @Override
        public Integer call() {
            CliSupport.require(gpus != null || model != null || vramMb != null, "At least one of --gpus, --model, --vram is required");
            if (gpus != null) {
                CliSupport.requirePositive(gpus, "gpus");
            }
            if (vramMb != null) {
                CliSupport.requirePositiveLong(vramMb, "vram");
            }
            if (hours != null) {
                CliSupport.requireRange(hours, 1, 720, "hours");
            }
            if (priority != null) {
                CliSupport.requireRange(priority, 0, 10, "priority");
            }
            CliSupport.requireOneOf(affinity, "affinity", Set.of("spread", "packed"));

            AllocationRequest request = new AllocationRequest(
                    System.getProperty("user.name", "unknown"),
                    null,
                    gpus == null ? 1 : gpus,
                    blankToNull(model),
                    vramMb,
                    exclusive,
                    hours == null ? 24 : hours,
                    priority == null ? 5 : priority,
                    preemptible,
                    AllocationAffinity.valueOf(affinity.toUpperCase(Locale.ROOT)),
                    blankToNull(labelSelector)
            );

            if (dryRun) {
                AllocationDecision decision = allocCommand.context().allocationService().dryRun(request);
                allocCommand.context().auditService().log("ALLOC_DRY_RUN", actor(), "alloc-dry-run", "gpus=" + request.gpuCount());
                printDecision(decision);
                return 0;
            }
            AllocationRecord record = allocCommand.context().allocationService().allocate(request);
            allocCommand.context().auditService().log("ALLOC_CREATE", actor(), record.id(), "gpus=" + record.devices().size());
            System.out.printf("Created allocation %s%n", record.id());
            printRecord(record);
            return 0;
        }
    }

    @Command(name = "list", description = "List allocations")
    static class ListCommand implements Callable<Integer> {
        @ParentCommand
        private AllocCommand allocCommand;

        @Option(names = "--mine") private boolean mine;
        @Option(names = "--tenant") private String tenant;
        @Option(names = "--node") private String node;
        @Option(names = "--status") private String status;

        @Override
        public Integer call() {
            if (status != null) {
                CliSupport.requireOneOf(status, "status", Set.of("active", "expired", "released"));
            }
            List<AllocationRecord> allocations = new ArrayList<>(allocCommand.context().allocationService().listAllocations());
            String currentUser = System.getProperty("user.name", "unknown");
            allocations.removeIf(record -> mine && !currentUser.equalsIgnoreCase(record.owner()));
            allocations.removeIf(record -> tenant != null && (record.tenant() == null || !tenant.equalsIgnoreCase(record.tenant())));
            allocations.removeIf(record -> node != null && (record.primaryNodeHostname() == null || !node.equalsIgnoreCase(record.primaryNodeHostname())));
            allocations.removeIf(record -> status != null && !record.status().name().equalsIgnoreCase(status));

            if (allocations.isEmpty()) {
                System.out.println("No allocations found.");
                return 0;
            }

            List<String[]> rows = new ArrayList<>();
            for (AllocationRecord record : allocations) {
                rows.add(new String[]{
                        record.id(),
                        record.owner(),
                        record.primaryNodeHostname() == null ? "-" : record.primaryNodeHostname(),
                        record.status().name(),
                        Integer.toString(record.devices().size()),
                        Boolean.toString(record.exclusiveNode()),
                        TS_FORMATTER.format(record.createdAt()),
                        TS_FORMATTER.format(record.expiresAt())
                });
            }
            System.out.println(AsciiTable.getTable(
                    new String[]{"Id", "Owner", "Node", "Status", "GPUs", "Exclusive", "Created", "Expires"},
                    rows.toArray(String[][]::new)
            ));
            return 0;
        }
    }

    @Command(name = "info", description = "Show allocation info")
    static class InfoCommand implements Callable<Integer> {
        @ParentCommand
        private AllocCommand allocCommand;

        @Option(names = "--id", required = true) private String id;
        @Override
        public Integer call() {
            CliSupport.requireNonBlank(id, "id");
            AllocationRecord record = allocCommand.context().allocationService().findAllocation(id)
                    .orElseThrow(() -> new IllegalArgumentException("Allocation not found: " + id));
            printRecord(record);
            return 0;
        }
    }

    @Command(name = "extend", description = "Extend an allocation")
    static class ExtendCommand implements Callable<Integer> {
        @ParentCommand
        private AllocCommand allocCommand;

        @Option(names = "--id", required = true) private String id;
        @Option(names = "--hours", required = true) private Integer hours;
        @Option(names = "--reason") private String reason = "unspecified";

        @Override
        public Integer call() {
            CliSupport.requireRange(hours, 1, 720, "hours");
            AllocationRecord record = allocCommand.context().allocationService().extendAllocation(id, hours);
            allocCommand.context().auditService().log("ALLOC_EXTEND", actor(), id, "hours=" + hours + ", reason=" + reason);
            System.out.printf("Allocation %s extended by %d hour(s). Reason: %s%n", id, hours, reason);
            printRecord(record);
            return 0;
        }
    }

    @Command(name = "release", description = "Release an allocation")
    static class ReleaseCommand implements Callable<Integer> {
        @ParentCommand
        private AllocCommand allocCommand;

        @Option(names = "--id", required = true) private String id;
        @Option(names = "--force") private boolean force;
        @Option(names = "--kill-process") private boolean killProcess;

        @Override
        public Integer call() {
            CliSupport.requireNonBlank(id, "id");
            AllocationRecord record = allocCommand.context().allocationService().releaseAllocation(id);
            allocCommand.context().auditService().log("ALLOC_RELEASE", actor(), id, "force=" + force + ", killProcess=" + killProcess);
            System.out.printf("Allocation %s released.%n", id);
            if (force || killProcess) {
                System.out.println("force/kill-process flags were validated. This build does not terminate external processes yet.");
            }
            printRecord(record);
            return 0;
        }
    }

    @Command(name = "reap", description = "Expire and reclaim timed-out allocations")
    static class ReapCommand implements Callable<Integer> {
        @ParentCommand
        private AllocCommand allocCommand;

        @Override
        public Integer call() {
            int expired = allocCommand.context().allocationService().reapExpiredAllocations();
            allocCommand.context().auditService().log("ALLOC_REAP", actor(), "allocation-reaper", "expired=" + expired);
            System.out.printf("Expired allocations reclaimed: %d%n", expired);
            return 0;
        }
    }

    @Command(name = "move", description = "Move an allocation to another node")
    static class MoveCommand implements Callable<Integer> {
        @Option(names = "--id", required = true) private String id;
        @Option(names = "--to-node", required = true) private String toNode;

        @Override
        public Integer call() {
            CliSupport.requireNonBlank(id, "id");
            CliSupport.requireNonBlank(toNode, "to-node");
            System.out.println("Live allocation migration backend is not implemented yet.");
            return 0;
        }
    }

    private static void printDecision(AllocationDecision decision) {
        System.out.println("Dry-run allocation candidate:");
        List<String[]> rows = new ArrayList<>();
        for (AllocationDevice device : decision.devices()) {
            rows.add(new String[]{
                    device.nodeHostname(),
                    device.vendor().name(),
                    safe(device.deviceId()),
                    safe(device.model()),
                    safe(device.pciBusId()),
                    safe(device.uuid())
            });
        }
        System.out.println(AsciiTable.getTable(
                new String[]{"Node", "Vendor", "DeviceId", "Model", "PCI", "UUID"},
                rows.toArray(String[][]::new)
        ));
        System.out.printf("Affinity: %s%n", decision.request().affinity().name());
        System.out.printf("Exclusive node: %s%n", decision.request().exclusiveNode());
        System.out.printf("Planned expiry: %s%n", TS_FORMATTER.format(decision.expiresAt()));
    }

    private static void printRecord(AllocationRecord record) {
        System.out.printf("Id: %s%n", record.id());
        System.out.printf("Owner: %s%n", record.owner());
        System.out.printf("Status: %s%n", record.status().name());
        System.out.printf("Node: %s%n", safe(record.primaryNodeHostname()));
        System.out.printf("GPUs: %d%n", record.devices().size());
        System.out.printf("Exclusive node: %s%n", record.exclusiveNode());
        System.out.printf("Created: %s%n", TS_FORMATTER.format(record.createdAt()));
        System.out.printf("Expires: %s%n", TS_FORMATTER.format(record.expiresAt()));
        if (record.releasedAt() != null) {
            System.out.printf("Released: %s%n", TS_FORMATTER.format(record.releasedAt()));
        }
        System.out.printf("Environment:%n");
        for (String env : buildEnvVars(record)) {
            System.out.printf("- %s%n", env);
        }
        if (!record.devices().isEmpty()) {
            System.out.println("Assigned devices:");
            List<String[]> rows = new ArrayList<>();
            for (AllocationDevice device : record.devices()) {
                rows.add(new String[]{
                        device.nodeHostname(),
                        device.vendor().name(),
                        safe(device.deviceId()),
                        safe(device.model()),
                        safe(device.pciBusId()),
                        safe(device.uuid())
                });
            }
            System.out.println(AsciiTable.getTable(
                    new String[]{"Node", "Vendor", "DeviceId", "Model", "PCI", "UUID"},
                    rows.toArray(String[][]::new)
            ));
        }
    }

    private static List<String> buildEnvVars(AllocationRecord record) {
        LinkedHashSet<String> vendors = new LinkedHashSet<>();
        List<String> ids = new ArrayList<>();
        List<String> uuids = new ArrayList<>();
        for (AllocationDevice device : record.devices()) {
            vendors.add(device.vendor().name());
            if (device.deviceId() != null) {
                ids.add(device.deviceId());
            }
            if (device.uuid() != null) {
                uuids.add(device.uuid());
            }
        }

        List<String> env = new ArrayList<>();
        String joinedIds = String.join(",", ids);
        String joinedUuids = String.join(",", uuids);
        if (vendors.size() == 1 && vendors.contains("NVIDIA")) {
            env.add("CUDA_VISIBLE_DEVICES=" + joinedIds);
            env.add("NVIDIA_VISIBLE_DEVICES=" + joinedIds);
        } else if (vendors.size() == 1 && vendors.contains("AMD")) {
            env.add("ROCR_VISIBLE_DEVICES=" + joinedIds);
        } else if (vendors.size() == 1 && vendors.contains("INTEL")) {
            env.add("ZE_AFFINITY_MASK=" + joinedIds);
        } else {
            env.add("GPUM_ALLOCATED_GPU_UUIDS=" + joinedUuids);
        }
        return env;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String actor() {
        return System.getProperty("user.name", "unknown");
    }
}
