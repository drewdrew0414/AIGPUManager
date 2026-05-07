package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
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
        @Override public Integer call() {
            CliSupport.requirePositive(count, "count");
            List<GpuPartitionRecord> created = partCommand.parent.createContext().governanceService().createPartitions(gpu, profile, count);
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
        @Override public Integer call() {
            CliSupport.require((id != null) ^ (gpuId != null), "Choose exactly one of --id or --all-on-gpu");
            int removed = id != null
                    ? partCommand.parent.createContext().governanceService().destroyPartitionById(id)
                    : partCommand.parent.createContext().governanceService().destroyPartitionsByGpu(gpuId);
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
                    partition.createdAt().toString()
            });
        }
        System.out.println(AsciiTable.getTable(
                new String[]{"Id", "Node", "GPU", "Model", "Profile", "Status", "Created"},
                rows.toArray(String[][]::new)
        ));
    }
}
