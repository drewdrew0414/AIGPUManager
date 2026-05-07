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
        Throwable root = rootCause(ex);
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        commandLine.getErr().printf("ERROR: %s%n", message);
        String commandPath = parseResult == null ? "gpum" : parseResult.asCommandLineList().stream()
                .map(line -> line.getCommandName())
                .reduce((left, right) -> left + " " + right)
                .orElse("gpum");
        commandLine.getErr().printf("HINT: run `%s --help` for command usage.%n", commandPath);
        if (commandLine.isUsageHelpRequested() || commandLine.isVersionHelpRequested()) {
            return commandLine.getCommandSpec().exitCodeOnUsageHelp();
        }
        return commandLine.getCommandSpec().exitCodeOnExecutionException();
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
