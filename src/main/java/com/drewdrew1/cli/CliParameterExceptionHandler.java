package com.drewdrew1.cli;

import picocli.CommandLine;

/** Converts parse and validation failures into consistent CLI errors. */
public class CliParameterExceptionHandler implements CommandLine.IParameterExceptionHandler {
    @Override
    public int handleParseException(CommandLine.ParameterException ex, String[] args) {
        CommandLine commandLine = ex.getCommandLine();
        commandLine.getErr().printf("ERROR: %s%n", ex.getMessage());
        commandLine.getErr().printf("HINT: run `%s --help` for command usage.%n", commandPath(commandLine));
        return commandLine.getCommandSpec().exitCodeOnInvalidInput();
    }

    private String commandPath(CommandLine commandLine) {
        if (commandLine == null) {
            return "gpum";
        }
        StringBuilder path = new StringBuilder();
        CommandLine current = commandLine;
        while (current != null) {
            if (!current.getCommandName().isBlank()) {
                if (!path.isEmpty()) {
                    path.insert(0, " ");
                }
                path.insert(0, current.getCommandName());
            }
            current = current.getParent();
        }
        return path.isEmpty() ? "gpum" : path.toString();
    }
}
