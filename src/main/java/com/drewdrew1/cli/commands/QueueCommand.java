package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.QueueEntry;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Exposes queue management commands for scheduling workflows. */
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
        @ParentCommand private QueueCommand queueCommand;
        @Option(names = "--full") private boolean full;
        @Option(names = "--position") private String position;
        @Option(names = "--estimate") private boolean estimate;
        @Override public Integer call() {
            if (position != null) {
                CliSupport.require("my".equalsIgnoreCase(position), "position currently only supports 'my'");
            }
            List<QueueEntry> entries = new ArrayList<>(queueCommand.parent.createContext().governanceService().listQueueEntries());
            if ("my".equalsIgnoreCase(position)) {
                String currentUser = System.getProperty("user.name", "unknown");
                entries.removeIf(entry -> !currentUser.equalsIgnoreCase(entry.owner()));
            }
            if (entries.isEmpty()) {
                System.out.println("Queue is empty.");
                return 0;
            }
            List<String[]> rows = new ArrayList<>();
            int order = 1;
            for (QueueEntry entry : entries) {
                String estimateText = estimate ? Integer.toString(order * 10) + "m" : "-";
                rows.add(new String[]{
                        Integer.toString(order++),
                        entry.id(),
                        entry.owner(),
                        Integer.toString(entry.priority()),
                        Integer.toString(entry.gpuCount()),
                        entry.modelFilter() == null ? "-" : entry.modelFilter(),
                        entry.status(),
                        full ? entry.reason() : estimateText
                });
            }
            System.out.println(AsciiTable.getTable(
                    new String[]{"Pos", "Id", "Owner", "Priority", "GPUs", "Model", "Status", full ? "Reason" : "Estimate"},
                    rows.toArray(String[][]::new)
            ));
            return 0;
        }
    }

    @Command(name = "promote", description = "Promote a queued request")
    static class PromoteCommand implements Callable<Integer> {
        @ParentCommand private QueueCommand queueCommand;
        @Option(names = "--id", required = true) private String id;
        @Option(names = "--val", required = true) private Integer val;
        @Override public Integer call() {
            CliSupport.requireRange(val, 1, 10, "val");
            QueueEntry updated = queueCommand.parent.createContext().governanceService().promoteQueueEntry(id, val);
            System.out.printf("Queue entry %s promoted to priority %d%n", updated.id(), updated.priority());
            return 0;
        }
    }

    @Command(name = "demote", description = "Demote a queued request")
    static class DemoteCommand implements Callable<Integer> {
        @ParentCommand private QueueCommand queueCommand;
        @Option(names = "--id", required = true) private String id;
        @Option(names = "--val", required = true) private Integer val;
        @Override public Integer call() {
            CliSupport.requireRange(val, 1, 10, "val");
            QueueEntry updated = queueCommand.parent.createContext().governanceService().demoteQueueEntry(id, val);
            System.out.printf("Queue entry %s demoted to priority %d%n", updated.id(), updated.priority());
            return 0;
        }
    }
}
