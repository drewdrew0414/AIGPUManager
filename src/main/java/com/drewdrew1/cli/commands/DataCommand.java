package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.OpsRecord;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/** Exposes dataset cache, snapshot, and checkpoint management commands. */
@Command(
        name = "data",
        mixinStandardHelpOptions = true,
        description = "Dataset cache, immutable snapshots, and checkpoint movement",
        subcommands = {
                DataCommand.CacheCommand.class,
                DataCommand.SnapshotCommand.class,
                DataCommand.CheckpointCommand.class,
                DataCommand.GdsCommand.class
        }
)
public class DataCommand implements Runnable {
    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "cache", description = "Plan or execute local NVMe dataset cache sync")
    static class CacheCommand implements Callable<Integer> {
        @ParentCommand private DataCommand dataCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--source", required = true) private String source;
        @Option(names = "--target", required = true) private Path target;
        @Option(names = "--execute") private boolean execute;

        @Override public Integer call() {
            CliSupport.requireNonBlank(source, "source");
            ComputeCommand.printPlan(dataCommand.parent.createContext().enterpriseOpsService().dataCache(name, source, target, execute));
            return 0;
        }
    }

    @Command(name = "snapshot", description = "Register immutable dataset snapshot metadata")
    static class SnapshotCommand implements Callable<Integer> {
        @ParentCommand private DataCommand dataCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--source", required = true) private String source;
        @Option(names = "--version", required = true) private String version;
        @Option(names = "--mount", required = true) private Path mountPath;

        @Override public Integer call() {
            OpsRecord record = dataCommand.parent.createContext().enterpriseOpsService()
                    .datasetSnapshot(name, source, version, mountPath);
            System.out.printf("Registered dataset snapshot %s version=%s id=%s%n", record.name(), version, record.id());
            return 0;
        }
    }

    @Command(name = "checkpoint", description = "Plan or push checkpoints to managed storage")
    static class CheckpointCommand implements Callable<Integer> {
        @ParentCommand private DataCommand dataCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--source", required = true) private Path source;
        @Option(names = "--dest", required = true) private String destination;
        @Option(names = "--execute") private boolean execute;

        @Override public Integer call() {
            ComputeCommand.printPlan(dataCommand.parent.createContext().enterpriseOpsService()
                    .checkpointPush(name, source, destination, execute));
            return 0;
        }
    }

    @Command(name = "gds", description = "Plan GPU Direct Storage readiness commands")
    static class GdsCommand implements Callable<Integer> {
        @ParentCommand private DataCommand dataCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--mount", required = true) private Path mount;
        @Option(names = "--mode", defaultValue = "read") private String mode;
        @Option(names = "--execute") private boolean execute;

        @Override public Integer call() {
            CliSupport.requireOneOf(mode, "mode", java.util.Set.of("read", "write", "readwrite"));
            ComputeCommand.printPlan(dataCommand.parent.createContext().enterpriseOpsService()
                    .gdsPlan(name, mount, mode, execute));
            return 0;
        }
    }
}
