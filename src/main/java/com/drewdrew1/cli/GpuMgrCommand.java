package com.drewdrew1.cli;

import com.drewdrew1.cli.commands.GpuCommand;
import com.drewdrew1.cli.commands.NodeCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.time.Duration;

@Command(
        name = "gpu-mgr",
        mixinStandardHelpOptions = true,
        description = "GPU inventory and resource management CLI",
        subcommands = {
                NodeCommand.class,
                GpuCommand.class
        }
)
public class GpuMgrCommand implements Runnable {
    @Option(
            names = "--db",
            defaultValue = "data/gpu-mgr.db",
            description = "SQLite database path"
    )
    private Path dbPath;

    @Option(
            names = "--command-timeout-sec",
            defaultValue = "10",
            description = "External hardware command timeout in seconds"
    )
    private int commandTimeoutSec;

    @Spec
    private CommandSpec spec;

    public AppContext createContext() {
        return new AppContext(dbPath, Duration.ofSeconds(commandTimeoutSec));
    }

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }
}
