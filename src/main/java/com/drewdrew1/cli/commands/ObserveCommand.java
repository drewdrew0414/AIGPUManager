package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.LogEntry;
import com.drewdrew1.core.model.LogQuery;
import com.drewdrew1.core.model.OpsRecord;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/** Exposes alert and profiler integration records. */
@Command(
        name = "observe",
        mixinStandardHelpOptions = true,
        description = "Alerts, profiling, and observability integration",
        subcommands = {
                ObserveCommand.AlertCommand.class,
                ObserveCommand.ProfileCommand.class,
                ObserveCommand.TelemetryCommand.class,
                ObserveCommand.LogStreamCommand.class
        }
)
public class ObserveCommand implements Runnable {
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "alert", description = "Create or list Slack/Teams/Email alert policies", subcommands = {
            AlertCommand.CreateCommand.class,
            AlertCommand.ListCommand.class
    })
    static class AlertCommand implements Runnable {
        @ParentCommand private ObserveCommand observeCommand;
        @Spec private CommandSpec spec;
        @Override public void run() { spec.commandLine().usage(System.out); }

        @Command(name = "create", description = "Register alert policy")
        static class CreateCommand implements Callable<Integer> {
            @ParentCommand private AlertCommand alertCommand;
            @Option(names = "--name", required = true) private String name;
            @Option(names = "--channel", required = true) private String channel;
            @Option(names = "--target", required = true) private String target;
            @Option(names = "--event", required = true) private String event;
            @Option(names = "--template", defaultValue = "gpum event {{event}} for {{resource}}") private String template;

            @Override public Integer call() {
                CliSupport.requireOneOf(channel, "channel", Set.of("slack", "teams", "email", "webhook"));
                OpsRecord record = alertCommand.observeCommand.parent.createContext().enterpriseOpsService()
                        .alert(name, channel, target, event, template);
                System.out.printf("Created alert policy %s id=%s%n", record.name(), record.id());
                return 0;
            }
        }

        @Command(name = "list", description = "List alert policies")
        static class ListCommand implements Callable<Integer> {
            @ParentCommand private AlertCommand alertCommand;
            @Override public Integer call() {
                ComputeCommand.printRecords(alertCommand.observeCommand.parent.createContext().enterpriseOpsService().list("observe", "alert"));
                return 0;
            }
        }
    }

    @Command(name = "profile", description = "Plan or run profiler wrapper")
    static class ProfileCommand implements Callable<Integer> {
        @ParentCommand private ObserveCommand observeCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--allocation-id") private String allocationId;
        @Option(names = "--tool", defaultValue = "nsys") private String tool;
        @Option(names = "--command", required = true) private String command;
        @Option(names = "--execute") private boolean execute;

        @Override public Integer call() {
            CliSupport.requireOneOf(tool, "tool", Set.of("nsys", "ncu", "shell"));
            ComputeCommand.printPlan(observeCommand.parent.createContext().enterpriseOpsService()
                    .profile(name, allocationId, tool, command, execute));
            return 0;
        }
    }

    @Command(name = "telemetry", description = "Create telemetry collection policy and optionally write current Prometheus snapshot")
    static class TelemetryCommand implements Callable<Integer> {
        @ParentCommand private ObserveCommand observeCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--interval-sec", defaultValue = "5") private Integer intervalSec;
        @Option(names = "--retention-hours", defaultValue = "168") private Integer retentionHours;
        @Option(names = "--path") private Path path;
        @Option(names = "--execute") private boolean execute;

        @Override public Integer call() {
            CliSupport.requireRange(intervalSec, 1, 3600, "interval-sec");
            CliSupport.requirePositive(retentionHours, "retention-hours");
            OpsRecord record = observeCommand.parent.createContext().enterpriseOpsService()
                    .telemetryPolicy(name, intervalSec, retentionHours, path);
            if (execute && path != null) {
                CliSupport.writeStringAtomic(path, observeCommand.parent.createContext().prometheusExportService().render());
            }
            System.out.printf("Telemetry policy %s id=%s status=%s%n", record.name(), record.id(), record.status());
            if (!execute) {
                System.out.println("Warning: dry-run only; re-run with --execute to write the current metrics snapshot.");
            }
            return 0;
        }
    }

    @Command(name = "log-stream", description = "Read recent centralized operational logs from SQLite")
    static class LogStreamCommand implements Callable<Integer> {
        @ParentCommand private ObserveCommand observeCommand;
        @Option(names = "--component") private String component;
        @Option(names = "--lines", defaultValue = "50") private Integer lines;
        @Option(names = "--follow") private boolean follow;

        @Override public Integer call() throws Exception {
            CliSupport.requireRange(lines, 1, 10_000, "lines");
            do {
                List<LogEntry> entries = observeCommand.parent.createContext().logService().query(new LogQuery(
                        null,
                        component == null || component.isBlank() ? null : component,
                        null,
                        null,
                        null,
                        null,
                        false,
                        lines
                ));
                if (entries.isEmpty()) {
                    System.out.println("No log entries found.");
                } else {
                    for (LogEntry entry : entries) {
                        System.out.printf("%s %-5s %-16s %-12s %s %s%n",
                                TS_FORMATTER.format(entry.createdAt()),
                                entry.level(),
                                entry.component(),
                                entry.category(),
                                entry.message(),
                                entry.context() == null ? "" : entry.context());
                    }
                }
                if (!follow) {
                    break;
                }
                Thread.sleep(1000L);
            } while (!Thread.currentThread().isInterrupted());
            return 0;
        }
    }
}
