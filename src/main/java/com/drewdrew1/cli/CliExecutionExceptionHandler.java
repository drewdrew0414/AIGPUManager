package com.drewdrew1.cli;

import picocli.CommandLine;

/** Converts uncaught command exceptions into user-facing CLI errors. */
public class CliExecutionExceptionHandler implements CommandLine.IExecutionExceptionHandler {
    @Override
    public int handleExecutionException(
            Exception ex,
            CommandLine commandLine,
            CommandLine.ParseResult parseResult
    ) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        commandLine.getErr().printf("ERROR: %s%n", message);
        if (commandLine.isUsageHelpRequested() || commandLine.isVersionHelpRequested()) {
            return commandLine.getCommandSpec().exitCodeOnUsageHelp();
        }
        return commandLine.getCommandSpec().exitCodeOnExecutionException();
    }
}
