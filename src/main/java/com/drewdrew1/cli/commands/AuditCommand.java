package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.AuditEvent;
import com.drewdrew1.core.model.AuditQuery;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
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

/** Exposes audit listing and lifecycle trace commands. */
@Command(
        name = "audit",
        mixinStandardHelpOptions = true,
        description = "Audit and lifecycle tracing",
        subcommands = {
                AuditCommand.ListCommand.class,
                AuditCommand.TraceCommand.class
        }
)
public class AuditCommand implements Runnable {
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "list", description = "List audit events")
    static class ListCommand implements Callable<Integer> {
        @ParentCommand
        private AuditCommand auditCommand;

        @Option(names = "--event") private String event;
        @Option(names = "--user") private String user;
        @Option(names = "--target") private String target;
        @Option(names = "--contains") private String contains;
        @Option(names = "--from") private String from;
        @Option(names = "--to") private String to;
        @Option(names = "--tail") private Integer tail;
        @Option(names = "--sort", defaultValue = "desc") private String sort;
        @Override public Integer call() {
            CliSupport.requireOneOf(sort, "sort", Set.of("asc", "desc"));
            if (tail != null) {
                CliSupport.requirePositive(tail, "tail");
            }
            Instant fromTs = from == null ? null : CliSupport.parseInstant(from, "from");
            Instant toTs = to == null ? null : CliSupport.parseInstant(to, "to");
            List<AuditEvent> events = new ArrayList<>(auditCommand.parent.createContext().auditService().query(new AuditQuery(
                    blankToNull(event),
                    blankToNull(user),
                    blankToNull(target),
                    blankToNull(contains),
                    fromTs,
                    toTs,
                    "asc".equalsIgnoreCase(sort),
                    tail
            )));
            if (events.isEmpty()) {
                System.out.println("No audit events found.");
                return 0;
            }
            List<String[]> rows = new ArrayList<>();
            for (AuditEvent auditEvent : events) {
                rows.add(new String[]{
                        Long.toString(auditEvent.id()),
                        TS_FORMATTER.format(auditEvent.createdAt()),
                        auditEvent.eventType(),
                        auditEvent.actor(),
                        auditEvent.target(),
                        auditEvent.details()
                });
            }
            System.out.println(AsciiTable.getTable(
                    new String[]{"Id", "Created", "Event", "Actor", "Target", "Details"},
                    rows.toArray(String[][]::new)
            ));
            return 0;
        }
    }

    @Command(name = "trace", description = "Trace a lifecycle by id")
    static class TraceCommand implements Callable<Integer> {
        @ParentCommand
        private AuditCommand auditCommand;

        @Parameters(index = "0", paramLabel = "ID") private String id;
        @Override public Integer call() {
            CliSupport.requireNonBlank(id, "id");
            List<AuditEvent> events = auditCommand.parent.createContext().auditService().traceByTarget(id);
            if (events.isEmpty()) {
                System.out.println("No audit trace found.");
                return 0;
            }
            List<String[]> rows = new ArrayList<>();
            for (AuditEvent auditEvent : events) {
                rows.add(new String[]{
                        Long.toString(auditEvent.id()),
                        TS_FORMATTER.format(auditEvent.createdAt()),
                        auditEvent.eventType(),
                        auditEvent.actor(),
                        auditEvent.details()
                });
            }
            System.out.println(AsciiTable.getTable(
                    new String[]{"Id", "Created", "Event", "Actor", "Details"},
                    rows.toArray(String[][]::new)
            ));
            return 0;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
