package com.drewdrew1;

import com.drewdrew1.cli.GpuMgrCommand;
import picocli.CommandLine;

public class App {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new GpuMgrCommand()).execute(args);
        System.exit(exitCode);
    }
}
