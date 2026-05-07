package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/** Exposes GPU partition and MIG-oriented command placeholders. */
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
        @Option(names = "--gpu", required = true) private String gpu;
        @Option(names = "--profile", required = true) private String profile;
        @Option(names = "--count", required = true) private Integer count;
        @Override public Integer call() {
            CliSupport.requirePositive(count, "count");
            System.out.println("MIG/partition creation backend is not implemented yet.");
            return 0;
        }
    }

    @Command(name = "list", description = "List GPU partitions")
    static class ListCommand implements Callable<Integer> {
        @Override public Integer call() {
            System.out.println("Partition listing backend is not implemented yet.");
            return 0;
        }
    }

    @Command(name = "destroy", description = "Destroy GPU partitions")
    static class DestroyCommand implements Callable<Integer> {
        @Option(names = "--id") private String id;
        @Option(names = "--all-on-gpu") private String gpuId;
        @Override public Integer call() {
            CliSupport.require((id != null) ^ (gpuId != null), "Choose exactly one of --id or --all-on-gpu");
            System.out.println("Partition destroy backend is not implemented yet.");
            return 0;
        }
    }

    @Command(name = "auto-optimize", description = "Recommend partition layout changes")
    static class AutoOptimizeCommand implements Callable<Integer> {
        @Override public Integer call() {
            System.out.println("Automatic partition optimization backend is not implemented yet.");
            return 0;
        }
    }
}
