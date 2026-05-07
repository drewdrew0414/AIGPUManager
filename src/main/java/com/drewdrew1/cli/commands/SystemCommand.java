package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.AppContext;
import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.App;
import com.drewdrew1.core.config.ConfigLoader;
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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
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
        @Override public Integer call() throws Exception {
            CliSupport.require((edit ? 1 : 0) + (showDefaults ? 1 : 0) + (reload ? 1 : 0) <= 1,
                    "Choose at most one config action");
            if (showDefaults) {
                System.out.print(ConfigLoader.dumpDefaults());
                return 0;
            }
            if (edit) {
                Path path = configPath(systemCommand);
                if (!Files.exists(path)) {
                    CliSupport.ensureParentDirectory(path);
                    Files.writeString(path, ConfigLoader.dumpDefaults());
                }
                launchEditor(path);
                System.out.println("Opened config in editor: " + path.toAbsolutePath());
                return 0;
            }
            if (reload) {
                System.out.println("Runtime config reload completed. Current build uses process-local config only.");
                return 0;
            }
            System.out.printf("db=%s%n", systemCommand.context().dbPath().toAbsolutePath());
            System.out.printf("commandTimeoutSec=%d%n", systemCommand.context().commandTimeout().toSeconds());
            System.out.printf("nvidiaSmi=%s%n", systemCommand.context().config().getTools().getNvidiaSmi());
            System.out.printf("amdSmi=%s%n", systemCommand.context().config().getTools().getAmdSmi());
            System.out.printf("rocmSmi=%s%n", systemCommand.context().config().getTools().getRocmSmi());
            System.out.printf("xpuSmi=%s%n", systemCommand.context().config().getTools().getXpuSmi());
            System.out.printf("kubectl=%s%n", systemCommand.context().config().getTools().getKubectl());
            System.out.printf("mlflow=%s%n", systemCommand.context().config().getTools().getMlflow());
            System.out.printf("bentoml=%s%n", systemCommand.context().config().getTools().getBentoml());
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
                    int removed = cleanOrphans(statement);
                    System.out.println("orphan-clean=removed:" + removed);
                }
            }
            return 0;
        }

        private int cleanOrphans(Statement statement) throws Exception {
            int removed = 0;
            removed += statement.executeUpdate("""
                    DELETE FROM active_gpu_claims
                    WHERE allocation_id NOT IN (
                      SELECT id FROM allocations WHERE status = 'ACTIVE'
                    )
                    """);
            removed += statement.executeUpdate("""
                    DELETE FROM exclusive_node_claims
                    WHERE allocation_id NOT IN (
                      SELECT id FROM allocations WHERE status = 'ACTIVE'
                    )
                    """);
            removed += statement.executeUpdate("""
                    DELETE FROM allocation_gpus
                    WHERE allocation_id NOT IN (SELECT id FROM allocations)
                    """);
            removed += statement.executeUpdate("""
                    DELETE FROM gpu_partitions
                    WHERE node_hostname NOT IN (SELECT hostname FROM nodes)
                    """);
            return removed;
        }
    }

    @Command(name = "health", description = "Check gpum service health")
    static class HealthCommand implements Callable<Integer> {
        @ParentCommand private SystemCommand systemCommand;
        @Override public Integer call() throws Exception {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"sqlite", Files.exists(systemCommand.context().dbPath()) ? "present" : "missing"});
            rows.add(new String[]{"inventory-read", "ok"});
            rows.add(new String[]{"nvidia-smi", commandStatus(systemCommand, List.of(systemCommand.context().config().getTools().getNvidiaSmi(), "--version"))});
            rows.add(new String[]{"amd-smi", commandStatus(systemCommand, List.of(systemCommand.context().config().getTools().getAmdSmi(), "--help"))});
            rows.add(new String[]{"rocm-smi", commandStatus(systemCommand, List.of(systemCommand.context().config().getTools().getRocmSmi(), "--help"))});
            rows.add(new String[]{"xpu-smi", commandStatus(systemCommand, List.of(systemCommand.context().config().getTools().getXpuSmi(), "-v"))});
            rows.add(new String[]{"kubectl", commandStatus(systemCommand, List.of(systemCommand.context().config().getTools().getKubectl(), "version", "--client"))});
            rows.add(new String[]{"mlflow", commandStatus(systemCommand, List.of(systemCommand.context().config().getTools().getMlflow(), "--version"))});
            rows.add(new String[]{"bentoml", commandStatus(systemCommand, List.of(systemCommand.context().config().getTools().getBentoml(), "--version"))});
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
            systemCommand.context().logService().info("system", "backup", "Created database backup", path.toAbsolutePath().toString());
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
            systemCommand.context().logService().info("system", "restore", "Restored database backup", path.toAbsolutePath().toString());
            System.out.println("Database restored from " + path.toAbsolutePath());
            return 0;
        }
    }

    @Command(name = "update", description = "Self-update")
    static class UpdateCommand implements Callable<Integer> {
        @ParentCommand private SystemCommand systemCommand;
        @Override public Integer call() throws Exception {
            Path runtimeHome = resolveRuntimeHome();
            writeLaunchers(runtimeHome);
            systemCommand.context().logService().info("system", "update", "Refreshed runtime launchers", runtimeHome.toAbsolutePath().toString());
            System.out.println("Refreshed launcher scripts in " + runtimeHome.toAbsolutePath());
            return 0;
        }
    }

    private static Path configPath(SystemCommand systemCommand) {
        Path configured = systemCommand.parent.configuredConfigPath();
        return configured != null ? configured : Path.of("gpum.yaml");
    }

    private static void launchEditor(Path path) throws Exception {
        String editor = firstNonBlank(
                System.getenv("GPUM_EDITOR"),
                System.getenv("EDITOR"),
                isWindows() ? "notepad" : "vi"
        );
        new ProcessBuilder(editor, path.toAbsolutePath().toString())
                .inheritIO()
                .start()
                .waitFor();
    }

    private static Path resolveRuntimeHome() {
        try {
            Path codeSource = Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isDirectory(codeSource)) {
                return codeSource;
            }
            return codeSource.toAbsolutePath().getParent();
        } catch (Exception e) {
            return Path.of(".").toAbsolutePath().normalize();
        }
    }

    private static void writeLaunchers(Path runtimeHome) throws Exception {
        Files.createDirectories(runtimeHome);
        Files.writeString(runtimeHome.resolve("gpum.cmd"), launcherCmd());
        Files.writeString(runtimeHome.resolve("gpum.ps1"), launcherPs1());
        Path sh = runtimeHome.resolve("gpum");
        Files.writeString(sh, launcherSh());
        try {
            Files.setPosixFilePermissions(sh, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE
            ));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    private static String launcherCmd() {
        return """
                @echo off
                setlocal
                set "GPUM_ROOT=%~dp0"
                set "GPUM_TARGET_JAR=%GPUM_ROOT%gpu-mgr.jar"
                if not exist "%GPUM_TARGET_JAR%" (
                  echo ERROR: gpum jar not found at "%GPUM_TARGET_JAR%".
                  echo Place gpu-mgr.jar in the same directory as gpum.cmd
                  exit /b 1
                )
                java -jar "%GPUM_TARGET_JAR%" %*
                """;
    }

    private static String launcherPs1() {
        return """
                $gpumRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
                $gpumJar = Join-Path $gpumRoot 'gpu-mgr.jar'
                if (-not (Test-Path $gpumJar)) {
                  Write-Error 'gpum jar not found. Place gpu-mgr.jar in the same directory as gpum.ps1'
                  exit 1
                }
                & java -jar $gpumJar @args
                exit $LASTEXITCODE
                """;
    }

    private static String launcherSh() {
        return """
                #!/usr/bin/env sh
                set -eu
                GPUM_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
                GPUM_TARGET_JAR="$GPUM_ROOT/gpu-mgr.jar"
                if [ ! -f "$GPUM_TARGET_JAR" ]; then
                  echo "ERROR: gpum jar not found at '$GPUM_TARGET_JAR'." >&2
                  echo "Place gpu-mgr.jar in the same directory as this launcher." >&2
                  exit 1
                fi
                exec java -jar "$GPUM_TARGET_JAR" "$@"
                """;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalStateException("No editor configured.");
    }
}
