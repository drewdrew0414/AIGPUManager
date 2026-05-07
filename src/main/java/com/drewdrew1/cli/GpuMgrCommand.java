package com.drewdrew1.cli;

import com.drewdrew1.cli.commands.AllocCommand;
import com.drewdrew1.cli.commands.AuditCommand;
import com.drewdrew1.cli.commands.GpuCommand;
import com.drewdrew1.cli.commands.NodeCommand;
import com.drewdrew1.cli.commands.PartCommand;
import com.drewdrew1.cli.commands.QueueCommand;
import com.drewdrew1.cli.commands.QuotaCommand;
import com.drewdrew1.cli.commands.ReportCommand;
import com.drewdrew1.cli.commands.SystemCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.time.Duration;

/** Defines the root gpum command and global runtime options. */
@Command(
        name = "gpum",
        mixinStandardHelpOptions = true,
        version = "gpum 1.0.0",
        description = "GPU inventory and resource management CLI",
        subcommands = {
                NodeCommand.class,
                GpuCommand.class,
                AllocCommand.class,
                PartCommand.class,
                QueueCommand.class,
                QuotaCommand.class,
                AuditCommand.class,
                ReportCommand.class,
                SystemCommand.class
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

    private transient AppContext appContext;

    public AppContext createContext() {
        if (appContext == null) {
            CliSupport.requireRange(commandTimeoutSec, 1, 300, "command timeout seconds");
            appContext = new AppContext(dbPath, Duration.ofSeconds(commandTimeoutSec));
        }
        return appContext;
    }

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }
}
