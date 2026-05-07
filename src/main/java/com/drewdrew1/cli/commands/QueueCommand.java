package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/** Exposes queue management commands for future scheduling workflows. */
@Command(
        name = "queue",
        mixinStandardHelpOptions = true,
        description = "Queue operations",
        subcommands = {
                QueueCommand.ListCommand.class,
                QueueCommand.PromoteCommand.class,
                QueueCommand.DemoteCommand.class
        }
)
public class QueueCommand implements Runnable {
    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "list", description = "List queue state")
    static class ListCommand implements Callable<Integer> {
        @Option(names = "--full") private boolean full;
        @Option(names = "--position") private String position;
        @Option(names = "--estimate") private boolean estimate;
        @Override public Integer call() {
            if (position != null) {
                CliSupport.require("my".equalsIgnoreCase(position), "position currently only supports 'my'");
            }
            System.out.println("Queue backend is not implemented yet.");
            return 0;
        }
    }

    @Command(name = "promote", description = "Promote a queued request")
    static class PromoteCommand implements Callable<Integer> {
        @Option(names = "--id", required = true) private String id;
        @Option(names = "--val", required = true) private Integer val;
        @Override public Integer call() {
            CliSupport.requireRange(val, 1, 10, "val");
            System.out.println("Queue promote backend is not implemented yet.");
            return 0;
        }
    }

    @Command(name = "demote", description = "Demote a queued request")
    static class DemoteCommand implements Callable<Integer> {
        @Option(names = "--id", required = true) private String id;
        @Option(names = "--val", required = true) private Integer val;
        @Override public Integer call() {
            CliSupport.requireRange(val, 1, 10, "val");
            System.out.println("Queue demote backend is not implemented yet.");
            return 0;
        }
    }
}
