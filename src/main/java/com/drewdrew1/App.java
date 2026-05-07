package com.drewdrew1;

import com.drewdrew1.cli.CliExecutionExceptionHandler;
import com.drewdrew1.cli.GpuMgrCommand;
import picocli.CommandLine;

/** Entry point for the gpum command line application. */
public class App {
    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new GpuMgrCommand());
        commandLine.setExecutionExceptionHandler(new CliExecutionExceptionHandler());
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }
}
