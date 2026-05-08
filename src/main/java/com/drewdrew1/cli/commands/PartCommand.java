package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuPartitionRecord;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Exposes GPU partition and MIG-oriented command operations. */
@Command(
        name = "part",
        mixinStandardHelpOptions = true,
        description = "GPU partitioning operations",
        subcommands = {
                PartCommand.CreateCommand.class,
                PartCommand.ListCommand.class,
                PartCommand.DestroyCommand.class,
                PartCommand.AutoOptimizeCommand.class
        }
)
public class PartCommand implements Runnable {
    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "create", description = "Create GPU partitions")
    static class CreateCommand implements Callable<Integer> {
        @ParentCommand private PartCommand partCommand;
        @Option(names = "--gpu", required = true) private String gpu;
        @Option(names = "--profile", required = true) private String profile;
        @Option(names = "--count", required = true) private Integer count;
        @Option(names = "--apply", description = "Apply NVIDIA MIG changes to hardware")
        private boolean apply;
        @Option(names = "--kill-processes", description = "Terminate local GPU-bound processes before partitioning")
        private boolean killProcesses;
        @Option(names = "--approval-id", description = "Approved request id for high-risk partition apply")
        private String approvalId;
        @Override public Integer call() {
            CliSupport.requirePositive(count, "count");
            if (!apply) {
                List<GpuPartitionRecord> created = partCommand.parent.createContext().governanceService().createPartitions(gpu, profile, count);
                System.out.printf("Created %d logical partition record(s).%n", created.size());
                print(created);
                return 0;
            }
            GpuDevice targetGpu = findGpu(partCommand, gpu);
            var approval = partCommand.parent.createContext().accessControlService().requireApprovalOrSubmit(
                    CliSupport.currentActor(),
                    null,
                    "PARTITION_CREATE",
                    "GPU",
                    targetGpu.nodeHostname() + ":" + CliSupport.safe(targetGpu.deviceId()),
                    "profile=" + profile + ", count=" + count + ", killProcesses=" + killProcesses,
                    com.drewdrew1.core.model.RbacRole.APPROVER,
                    approvalId
            );
            if (approval != null) {
                System.out.printf("Partition apply requires approval. Request created: %s%n", approval.id());
                return 0;
            }
            partCommand.parent.createContext().accessControlService().requireRole(
                    CliSupport.currentActor(),
                    com.drewdrew1.core.model.RbacRole.OPERATOR,
                    null,
                    "OPERATOR role is required to apply GPU partitions."
            );
            List<GpuPartitionRecord> created = partCommand.parent.createContext().partitionControlService()
                    .createNvidiaMigPartitions(targetGpu, profile, count, killProcesses, approvalId);
            System.out.printf("Created %d partition record(s).%n", created.size());
            print(created);
            return 0;
        }
    }

    @Command(name = "list", description = "List GPU partitions")
    static class ListCommand implements Callable<Integer> {
        @ParentCommand private PartCommand partCommand;
        @Override public Integer call() {
            List<GpuPartitionRecord> partitions = partCommand.parent.createContext().governanceService().listPartitions();
            if (partitions.isEmpty()) {
                System.out.println("No partition records found.");
                return 0;
            }
            print(partitions);
            return 0;
        }
    }

    @Command(name = "destroy", description = "Destroy GPU partitions")
    static class DestroyCommand implements Callable<Integer> {
        @ParentCommand private PartCommand partCommand;
        @Option(names = "--id") private String id;
        @Option(names = "--all-on-gpu") private String gpuId;
        @Option(names = "--apply", description = "Destroy hardware MIG instances as well as DB records")
        private boolean apply;
        @Option(names = "--kill-processes", description = "Terminate local GPU-bound processes before destroy")
        private boolean killProcesses;
        @Option(names = "--approval-id", description = "Approved request id for high-risk partition destroy")
        private String approvalId;
        @Override public Integer call() {
            CliSupport.require((id != null) ^ (gpuId != null), "Choose exactly one of --id or --all-on-gpu");
            int removed;
            if (!apply) {
                removed = id != null
                        ? partCommand.parent.createContext().governanceService().destroyPartitionById(id)
                        : partCommand.parent.createContext().governanceService().destroyPartitionsByGpu(gpuId);
            } else {
                List<GpuPartitionRecord> records = selectRecords(partCommand, id, gpuId);
                CliSupport.require(!records.isEmpty(), "No partition records matched the selector.");
                String resourceId = id != null ? id : gpuId;
                var approval = partCommand.parent.createContext().accessControlService().requireApprovalOrSubmit(
                        CliSupport.currentActor(),
                        null,
                        "PARTITION_DESTROY",
                        "PARTITION",
                        resourceId,
                        "count=" + records.size() + ", killProcesses=" + killProcesses,
                        com.drewdrew1.core.model.RbacRole.APPROVER,
                        approvalId
                );
                if (approval != null) {
                    System.out.printf("Partition destroy requires approval. Request created: %s%n", approval.id());
                    return 0;
                }
                partCommand.parent.createContext().accessControlService().requireRole(
                        CliSupport.currentActor(),
                        com.drewdrew1.core.model.RbacRole.OPERATOR,
                        null,
                        "OPERATOR role is required to destroy GPU partitions."
                );
                removed = partCommand.parent.createContext().partitionControlService().destroyPartitions(records, killProcesses);
            }
            System.out.printf("Removed %d partition record(s).%n", removed);
            return 0;
        }
    }

    @Command(name = "auto-optimize", description = "Recommend partition layout changes")
    static class AutoOptimizeCommand implements Callable<Integer> {
        @ParentCommand private PartCommand partCommand;
        @Override public Integer call() {
            List<String> recommendations = partCommand.parent.createContext().governanceService().autoOptimizeRecommendations();
            System.out.println("Partition optimization recommendations:");
            for (String recommendation : recommendations) {
                System.out.println("- " + recommendation);
            }
            return 0;
        }
    }

    private static void print(List<GpuPartitionRecord> partitions) {
        List<String[]> rows = new ArrayList<>();
        for (GpuPartitionRecord partition : partitions) {
            rows.add(new String[]{
                    partition.id(),
                    partition.nodeHostname(),
                    partition.gpuDeviceId(),
                    partition.gpuModel(),
                    partition.profile(),
                    partition.status(),
                    partition.hardwareApplied() ? "yes" : "no",
                    partition.hardwareGpuInstanceId() == null ? "-" : partition.hardwareGpuInstanceId(),
                    partition.createdAt().toString()
            });
        }
        System.out.println(AsciiTable.getTable(
                new String[]{"Id", "Node", "GPU", "Model", "Profile", "Status", "Applied", "GI", "Created"},
                rows.toArray(String[][]::new)
        ));
    }

    private static GpuDevice findGpu(PartCommand partCommand, String selector) {
        List<GpuDevice> matches = partCommand.parent.createContext().inventoryRepository().listGpus().stream()
                .filter(gpu -> selector.equalsIgnoreCase(gpu.nodeHostname() + ":" + CliSupport.safe(gpu.deviceId()))
                        || selector.equalsIgnoreCase(CliSupport.safe(gpu.uuid()))
                        || selector.equalsIgnoreCase(CliSupport.safe(gpu.deviceId())))
                .toList();
        CliSupport.require(!matches.isEmpty(), "GPU not found in current inventory: " + selector);
        CliSupport.require(matches.size() == 1, "GPU selector is ambiguous: " + selector);
        return matches.getFirst();
    }

    private static List<GpuPartitionRecord> selectRecords(PartCommand partCommand, String id, String gpuId) {
        List<GpuPartitionRecord> records = new ArrayList<>(partCommand.parent.createContext().governanceService().listPartitions());
        if (id != null) {
            records.removeIf(record -> !record.id().equalsIgnoreCase(id));
        } else {
            records.removeIf(record -> !record.gpuDeviceId().equalsIgnoreCase(gpuId)
                    && !(record.nodeHostname() + ":" + record.gpuDeviceId()).equalsIgnoreCase(gpuId));
        }
        return records;
    }
}
