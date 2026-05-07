package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.QuotaAlertPolicy;
import com.drewdrew1.core.model.QuotaUsage;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Exposes quota and governance related commands. */
@Command(
        name = "quota",
        mixinStandardHelpOptions = true,
        description = "Quota and governance operations",
        subcommands = {
                QuotaCommand.StatusCommand.class,
                QuotaCommand.SetCommand.class,
                QuotaCommand.AlertCommand.class
        }
)
public class QuotaCommand implements Runnable {
    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "status", description = "Show quota status")
    static class StatusCommand implements Callable<Integer> {
        @ParentCommand private QuotaCommand quotaCommand;
        @Option(names = "--user") private String user;
        @Option(names = "--tenant") private String tenant;
        @Option(names = "--remaining") private boolean remaining;
        @Override public Integer call() {
            List<QuotaUsage> usage;
            if (user != null) {
                usage = quotaCommand.parent.createContext().governanceService().quotaUsage(user).map(List::of).orElse(List.of());
            } else if (tenant != null) {
                usage = quotaCommand.parent.createContext().governanceService().quotaUsage(tenant).map(List::of).orElse(List.of());
            } else {
                usage = quotaCommand.parent.createContext().governanceService().listQuotaUsage();
            }
            if (usage.isEmpty()) {
                System.out.println("No quota policies found.");
                return 0;
            }
            List<String[]> rows = new ArrayList<>();
            for (QuotaUsage item : usage) {
                rows.add(new String[]{
                        item.name(),
                        value(item.maxGpus()),
                        value(item.maxVramMb()),
                        value(item.maxLeaseHours()),
                        Integer.toString(item.activeGpuCount()),
                        Long.toString(item.activeVramMb()),
                        Integer.toString(item.activeLeaseHours()),
                        remaining ? value(item.remainingGpus()) : Boolean.toString(item.burstAllow()),
                        remaining ? value(item.remainingVramMb()) : "-"
                });
            }
            System.out.println(AsciiTable.getTable(
                    new String[]{"Name", "MaxGPUs", "MaxVRAM", "MaxHours", "ActiveGPUs", "ActiveVRAM", "ActiveHours", remaining ? "RemainGPUs" : "Burst", remaining ? "RemainVRAM" : "-"},
                    rows.toArray(String[][]::new)
            ));
            return 0;
        }
    }

    @Command(name = "set", description = "Set quota policy")
    static class SetCommand implements Callable<Integer> {
        @ParentCommand private QuotaCommand quotaCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--max-gpus") private Integer maxGpus;
        @Option(names = "--max-vram") private Long maxVram;
        @Option(names = "--max-lease-hours") private Integer maxLeaseHours;
        @Option(names = "--burst-allow") private boolean burstAllow;
        @Override public Integer call() {
            CliSupport.require(maxGpus != null || maxVram != null || maxLeaseHours != null,
                    "At least one quota limit must be set");
            if (maxGpus != null) CliSupport.requirePositive(maxGpus, "max-gpus");
            if (maxVram != null) CliSupport.requirePositiveLong(maxVram, "max-vram");
            if (maxLeaseHours != null) CliSupport.requirePositive(maxLeaseHours, "max-lease-hours");
            quotaCommand.parent.createContext().governanceService()
                    .saveQuotaPolicy(name, maxGpus, maxVram, maxLeaseHours, burstAllow);
            System.out.printf("Quota policy saved for %s%n", name);
            return 0;
        }
    }

    @Command(name = "alert", description = "Configure quota alerts")
    static class AlertCommand implements Callable<Integer> {
        @ParentCommand private QuotaCommand quotaCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--threshold", split = ",", required = true) private int[] thresholds;
        @Override public Integer call() {
            List<Integer> values = new ArrayList<>();
            for (int threshold : thresholds) {
                CliSupport.require(threshold == 80 || threshold == 90, "Only 80 and 90 percent thresholds are supported");
                values.add(threshold);
            }
            quotaCommand.parent.createContext().governanceService().saveQuotaAlerts(name, values);
            QuotaAlertPolicy policy = quotaCommand.parent.createContext().governanceService().quotaAlertPolicy(name).orElseThrow();
            System.out.printf("Quota alerts saved for %s: %s%n", policy.name(), policy.thresholds());
            return 0;
        }
    }

    private static String value(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
