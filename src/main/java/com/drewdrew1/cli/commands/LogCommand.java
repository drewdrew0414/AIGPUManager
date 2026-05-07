package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.LogEntry;
import com.drewdrew1.core.model.LogQuery;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/** Exposes persistent operational log commands backed by SQLite. */
@Command(
        name = "log",
        mixinStandardHelpOptions = true,
        description = "Operational log storage and query operations",
        subcommands = {
                LogCommand.WriteCommand.class,
                LogCommand.ListCommand.class,
                LogCommand.TailCommand.class
        }
)
public class LogCommand implements Runnable {
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @ParentCommand
    private GpuMgrCommand parent;

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    @Command(name = "write", description = "Persist a log entry")
    static class WriteCommand implements Callable<Integer> {
        @ParentCommand
        private LogCommand logCommand;

        @Option(names = "--level", defaultValue = "INFO")
        private String level;

        @Option(names = "--component", required = true)
        private String component;

        @Option(names = "--category", required = true)
        private String category;

        @Option(names = "--message", required = true)
        private String message;

        @Option(names = "--context")
        private String context;

        @Override
        public Integer call() {
            CliSupport.requireOneOf(level, "level", Set.of("info", "warn", "error", "debug"));
            CliSupport.requireNonBlank(component, "component");
            CliSupport.requireNonBlank(category, "category");
            CliSupport.requireNonBlank(message, "message");
            logCommand.parent.createContext().logService()
                    .log(level.toUpperCase(), component, category, message, context);
            System.out.println("Log entry stored.");
            return 0;
        }
    }

    @Command(name = "list", description = "Query persisted log entries")
    static class ListCommand implements Callable<Integer> {
        @ParentCommand
        private LogCommand logCommand;

        @Option(names = "--level")
        private String level;

        @Option(names = "--component")
        private String component;

        @Option(names = "--category")
        private String category;

        @Option(names = "--contains")
        private String contains;

        @Option(names = "--from")
        private String from;

        @Option(names = "--to")
        private String to;

        @Option(names = "--sort", defaultValue = "desc")
        private String sort;

        @Option(names = "--limit")
        private Integer limit;

        @Override
        public Integer call() {
            CliSupport.requireOneOf(sort, "sort", Set.of("asc", "desc"));
            if (limit != null) {
                CliSupport.requirePositive(limit, "limit");
            }
            Instant fromTs = from == null ? null : CliSupport.parseInstant(from, "from");
            Instant toTs = to == null ? null : CliSupport.parseInstant(to, "to");
            List<LogEntry> entries = logCommand.parent.createContext().logService().query(new LogQuery(
                    blankToNull(level),
                    blankToNull(component),
                    blankToNull(category),
                    blankToNull(contains),
                    fromTs,
                    toTs,
                    "asc".equalsIgnoreCase(sort),
                    limit
            ));
            print(entries);
            return 0;
        }
    }

    @Command(name = "tail", description = "Show the most recent log entries")
    static class TailCommand implements Callable<Integer> {
        @ParentCommand
        private LogCommand logCommand;

        @Option(names = "--lines", defaultValue = "20")
        private int lines;

        @Override
        public Integer call() {
            CliSupport.requireRange(lines, 1, 10_000, "lines");
            List<LogEntry> entries = logCommand.parent.createContext().logService().query(new LogQuery(
                    null, null, null, null, null, null, false, lines
            ));
            print(entries);
            return 0;
        }
    }

    private static void print(List<LogEntry> entries) {
        if (entries.isEmpty()) {
            System.out.println("No log entries found.");
            return;
        }
        List<String[]> rows = new ArrayList<>();
        for (LogEntry entry : entries) {
            rows.add(new String[]{
                    Long.toString(entry.id()),
                    TS_FORMATTER.format(entry.createdAt()),
                    entry.level(),
                    entry.component(),
                    entry.category(),
                    entry.message(),
                    entry.context() == null ? "-" : entry.context()
            });
        }
        System.out.println(AsciiTable.getTable(
                new String[]{"Id", "Created", "Level", "Component", "Category", "Message", "Context"},
                rows.toArray(String[][]::new)
        ));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
