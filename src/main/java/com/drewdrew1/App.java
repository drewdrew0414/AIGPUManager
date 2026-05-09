package com.drewdrew1;

import com.drewdrew1.cli.CliExecutionExceptionHandler;
import com.drewdrew1.cli.CliParameterExceptionHandler;
import com.drewdrew1.cli.GpuMgrCommand;
import picocli.CommandLine;

/** Entry point for the gpum command line application. */
public class App {
    public static void main(String[] args) {
        CommandLine commandLine = createCommandLine();
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    public static CommandLine createCommandLine() {
        CommandLine commandLine = new CommandLine(new GpuMgrCommand());
        enableStandardHelp(commandLine);
        commandLine.setExecutionExceptionHandler(new CliExecutionExceptionHandler());
        commandLine.setParameterExceptionHandler(new CliParameterExceptionHandler());
        return commandLine;
    }

    private static void enableStandardHelp(CommandLine commandLine) {
        commandLine.getCommandSpec().mixinStandardHelpOptions(true);
        if (commandLine.getCommandSpec().findOption("--help") == null) {
            commandLine.getCommandSpec().addOption(CommandLine.Model.OptionSpec
                    .builder("-h", "--help")
                    .usageHelp(true)
                    .description("Show this help message and exit.")
                    .build());
        }
        if (commandLine.getCommandSpec().findOption("--version") == null) {
            commandLine.getCommandSpec().addOption(CommandLine.Model.OptionSpec
                    .builder("-V", "--version")
                    .versionHelp(true)
                    .description("Print version information and exit.")
                    .build());
        }
        for (CommandLine child : commandLine.getSubcommands().values()) {
            enableStandardHelp(child);
        }
    }
}
