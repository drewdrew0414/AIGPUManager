package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/** Exposes quota and governance related command placeholders. */
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
        @Option(names = "--user") private String user;
        @Option(names = "--tenant") private String tenant;
        @Option(names = "--remaining") private boolean remaining;
        @Override public Integer call() {
            System.out.println("Quota status backend is not implemented yet.");
            return 0;
        }
    }

    @Command(name = "set", description = "Set quota policy")
    static class SetCommand implements Callable<Integer> {
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
            System.out.println("Quota set backend is not implemented yet.");
            return 0;
        }
    }

    @Command(name = "alert", description = "Configure quota alerts")
    static class AlertCommand implements Callable<Integer> {
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--threshold", split = ",", required = true) private int[] thresholds;
        @Override public Integer call() {
            for (int threshold : thresholds) {
                CliSupport.require(threshold == 80 || threshold == 90, "Only 80 and 90 percent thresholds are supported");
            }
            System.out.println("Quota alert backend is not implemented yet.");
            return 0;
        }
    }
}
