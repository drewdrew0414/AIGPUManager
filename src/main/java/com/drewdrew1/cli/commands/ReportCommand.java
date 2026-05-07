package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.Set;
import java.util.concurrent.Callable;

/** Exposes usage and billing report command placeholders. */
@Command(
        name = "report",
        mixinStandardHelpOptions = true,
        description = "Reporting operations",
        subcommands = {
                ReportCommand.UsageCommand.class,
                ReportCommand.BillingCommand.class
        }
)
public class ReportCommand implements Runnable {
    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "usage", description = "Generate usage report")
    static class UsageCommand implements Callable<Integer> {
        @Option(names = "--format", required = true) private String format;
        @Option(names = "--by", required = true) private String by;
        @Override public Integer call() {
            CliSupport.requireOneOf(format, "format", Set.of("pdf", "csv", "json"));
            CliSupport.requireOneOf(by, "by", Set.of("user", "tenant", "model"));
            System.out.println("Usage reporting backend is not implemented yet.");
            return 0;
        }
    }

    @Command(name = "billing", description = "Generate billing simulation")
    static class BillingCommand implements Callable<Integer> {
        @Option(names = "--rate-card", required = true) private String rateCard;
        @Override public Integer call() {
            CliSupport.requireNonBlank(rateCard, "rate-card");
            System.out.println("Billing backend is not implemented yet.");
            return 0;
        }
    }
}
