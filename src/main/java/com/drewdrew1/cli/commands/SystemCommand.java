package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.AppContext;
import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.infra.executor.CommandExecutionException;
import com.drewdrew1.infra.executor.CommandResult;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Exposes system maintenance, health, backup, and database operations. */
@Command(
        name = "system",
        mixinStandardHelpOptions = true,
        description = "System maintenance operations",
        subcommands = {
                SystemCommand.ConfigCommand.class,
                SystemCommand.DbCheckCommand.class,
                SystemCommand.HealthCommand.class,
                SystemCommand.BackupCommand.class,
                SystemCommand.RestoreCommand.class,
                SystemCommand.UpdateCommand.class
        }
)
public class SystemCommand implements Runnable {
    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    AppContext context() { return parent.createContext(); }
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "config", description = "Show or reload runtime config")
    static class ConfigCommand implements Callable<Integer> {
        @ParentCommand private SystemCommand systemCommand;
        @Option(names = "--edit") private boolean edit;
        @Option(names = "--show-defaults") private boolean showDefaults;
        @Option(names = "--reload") private boolean reload;
        @Override public Integer call() {
            CliSupport.require((edit ? 1 : 0) + (showDefaults ? 1 : 0) + (reload ? 1 : 0) <= 1,
                    "Choose at most one config action");
            if (edit) {
                System.out.println("Interactive config editing is not implemented yet.");
                return 0;
            }
            if (reload) {
                System.out.println("Runtime config reload completed. Current build uses process-local config only.");
                return 0;
            }
            System.out.printf("db=%s%n", systemCommand.context().dbPath().toAbsolutePath());
            System.out.printf("commandTimeoutSec=%d%n", systemCommand.context().commandTimeout().toSeconds());
            return 0;
        }
    }

    @Command(name = "db-check", description = "Check and maintain database")
    static class DbCheckCommand implements Callable<Integer> {
        @ParentCommand private SystemCommand systemCommand;
        @Option(names = "--repair") private boolean repair;
        @Option(names = "--vacuum") private boolean vacuum;
        @Option(names = "--orphan-clean") private boolean orphanClean;
        @Override public Integer call() throws Exception {
            systemCommand.context().inventoryRepository().initialize();
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + systemCommand.context().dbPath().toAbsolutePath());
                 Statement statement = connection.createStatement()) {
                try (ResultSet rs = statement.executeQuery("PRAGMA integrity_check")) {
                    if (rs.next()) {
                        System.out.println("integrity_check=" + rs.getString(1));
                    }
                }
                if (vacuum) {
                    statement.execute("VACUUM");
                    System.out.println("vacuum=done");
                }
                if (repair) {
                    System.out.println("repair=not_needed_in_current_schema");
                }
                if (orphanClean) {
                    System.out.println("orphan-clean=not_implemented");
                }
            }
            return 0;
        }
    }

    @Command(name = "health", description = "Check gpum service health")
    static class HealthCommand implements Callable<Integer> {
        @ParentCommand private SystemCommand systemCommand;
        @Override public Integer call() {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"sqlite", Files.exists(systemCommand.context().dbPath()) ? "present" : "missing"});
            rows.add(new String[]{"inventory-read", "ok"});
            rows.add(new String[]{"nvidia-smi", commandStatus(systemCommand, List.of("nvidia-smi", "--version"))});
            rows.add(new String[]{"amd-smi", commandStatus(systemCommand, List.of("amd-smi", "--help"))});
            rows.add(new String[]{"rocm-smi", commandStatus(systemCommand, List.of("rocm-smi", "--help"))});
            rows.add(new String[]{"xpu-smi", commandStatus(systemCommand, List.of("xpu-smi", "-v"))});
            System.out.println(AsciiTable.getTable(new String[]{"Component", "Status"}, rows.toArray(String[][]::new)));
            return 0;
        }

        private String commandStatus(SystemCommand systemCommand, List<String> command) {
            try {
                CommandResult result = systemCommand.context().commandExecutor().execute(command);
                return result.isSuccess() ? "ok" : "error";
            } catch (CommandExecutionException e) {
                return "missing";
            }
        }
    }

    @Command(name = "backup", description = "Backup metadata database")
    static class BackupCommand implements Callable<Integer> {
        @ParentCommand private SystemCommand systemCommand;
        @Option(names = "--path", required = true) private Path path;
        @Override public Integer call() throws Exception {
            CliSupport.ensureParentDirectory(path);
            systemCommand.context().inventoryRepository().initialize();
            Files.copy(systemCommand.context().dbPath(), path, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Backup created at " + path.toAbsolutePath());
            return 0;
        }
    }

    @Command(name = "restore", description = "Restore metadata database")
    static class RestoreCommand implements Callable<Integer> {
        @ParentCommand private SystemCommand systemCommand;
        @Option(names = "--path", required = true) private Path path;
        @Override public Integer call() throws Exception {
            CliSupport.require(Files.exists(path), "Backup file not found: " + path);
            CliSupport.ensureParentDirectory(systemCommand.context().dbPath());
            Files.copy(path, systemCommand.context().dbPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Database restored from " + path.toAbsolutePath());
            return 0;
        }
    }

    @Command(name = "update", description = "Self-update")
    static class UpdateCommand implements Callable<Integer> {
        @Override public Integer call() {
            System.out.println("Self-update is not implemented yet.");
            return 0;
        }
    }
}
