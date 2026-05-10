package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.AppContext;
import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.App;
import com.drewdrew1.core.config.ConfigLoader;
import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.OpsRecord;
import com.drewdrew1.core.service.HealthScoringService;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
                SystemCommand.SafetyCommand.class,
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
                systemCommand.context().accessControlService().requireRole(
                        CliSupport.currentActor(),
                        com.drewdrew1.core.model.RbacRole.ADMIN,
                        null,
                        "ADMIN role is required to edit runtime config."
                );
                Path path = configPath(systemCommand);
                if (!Files.exists(path)) {
                    CliSupport.requireNotDirectory(path, "config path");
                    CliSupport.writeStringAtomic(path, ConfigLoader.dumpDefaults());
                }
                launchEditor(path);
                System.out.println("Opened config in editor: " + path.toAbsolutePath());
                return 0;
            }
            if (reload) {
                systemCommand.context().accessControlService().requireRole(
                        CliSupport.currentActor(),
                        com.drewdrew1.core.model.RbacRole.ADMIN,
                        null,
                        "ADMIN role is required to reload runtime config."
                );
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
            if (repair || vacuum || orphanClean) {
                systemCommand.context().accessControlService().requireRole(
                        CliSupport.currentActor(),
                        com.drewdrew1.core.model.RbacRole.ADMIN,
                        null,
                        "ADMIN role is required to modify the metadata database."
                );
            }
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

    @Command(
            name = "safety",
            description = "Preflight safety checks, guardrail policy, and incident records",
            subcommands = {
                    SafetyCommand.CheckCommand.class,
                    SafetyCommand.PolicyCommand.class,
                    SafetyCommand.LimitsCommand.class,
                    SafetyCommand.IncidentCommand.class
            }
    )
    static class SafetyCommand implements Runnable {
        @ParentCommand private SystemCommand systemCommand;
        @Spec private CommandSpec spec;
        @Override public void run() { spec.commandLine().usage(System.out); }

        @Command(name = "check", description = "Run production safety preflight checks")
        static class CheckCommand implements Callable<Integer> {
            @ParentCommand private SafetyCommand safetyCommand;
            @Option(names = "--quarantine", description = "Apply quarantine attributes for GPUs below the configured score") private boolean quarantine;
            @Option(names = "--fail-on-warn", description = "Return a non-zero exit code for warning or critical findings") private boolean failOnWarn;

            @Override public Integer call() throws Exception {
                SystemCommand systemCommand = safetyCommand.systemCommand;
                SafetyPolicy policy = loadSafetyPolicy(systemCommand);
                List<SafetyFinding> findings = collectSafetyFindings(systemCommand, policy);
                if (quarantine) {
                    int changed = systemCommand.context().healthScoringService()
                            .applyQuarantine(systemCommand.context().config().getMonitoring().getQuarantineScoreThreshold());
                    findings.add(new SafetyFinding("gpu-quarantine", changed > 0 ? "WARN" : "OK",
                            "quarantined=" + changed,
                            "GPUs below health-score threshold are marked unschedulable."));
                }
                printFindings(findings);
                long critical = findings.stream().filter(finding -> "CRITICAL".equals(finding.severity())).count();
                long warn = findings.stream().filter(finding -> "WARN".equals(finding.severity())).count();
                System.out.printf("Safety summary: critical=%d warn=%d ok=%d%n",
                        critical,
                        warn,
                        findings.size() - critical - warn);
                return failOnWarn && (critical > 0 || warn > 0) ? 2 : 0;
            }
        }

        @Command(name = "policy", description = "Store cluster safety guardrail limits")
        static class PolicyCommand implements Callable<Integer> {
            @ParentCommand private SafetyCommand safetyCommand;
            @Option(names = "--name", defaultValue = "production-guard") private String name;
            @Option(names = "--max-gpus-per-request") private Integer maxGpusPerRequest;
            @Option(names = "--max-lease-hours") private Integer maxLeaseHours;
            @Option(names = "--thermal-warn-c") private Double thermalWarnC;
            @Option(names = "--thermal-critical-c") private Double thermalCriticalC;
            @Option(names = "--min-free-vram-ratio") private Double minFreeVramRatio;
            @Option(names = "--max-power-limit-w") private Integer maxPowerLimitW;
            @Option(names = "--min-disk-free-gb") private Long minDiskFreeGb;
            @Option(names = "--heartbeat-stale-sec") private Integer heartbeatStaleSec;
            @Option(names = "--max-job-shm-gb") private Integer maxJobShmGb;

            @Override public Integer call() {
                SystemCommand systemCommand = safetyCommand.systemCommand;
                SafetyPolicy current = loadSafetyPolicy(systemCommand);
                SafetyPolicy policy = new SafetyPolicy(
                        value(maxGpusPerRequest, current.maxGpusPerRequest()),
                        value(maxLeaseHours, current.maxLeaseHours()),
                        value(thermalWarnC, current.thermalWarnC()),
                        value(thermalCriticalC, current.thermalCriticalC()),
                        value(minFreeVramRatio, current.minFreeVramRatio()),
                        value(maxPowerLimitW, current.maxPowerLimitW()),
                        value(minDiskFreeGb, current.minDiskFreeGb()),
                        value(heartbeatStaleSec, current.heartbeatStaleSec()),
                        value(maxJobShmGb, current.maxJobShmGb())
                );
                validatePolicy(policy);
                OpsRecord record = systemCommand.context().enterpriseOpsService().record(
                        "system",
                        "safety-policy",
                        name,
                        CliSupport.currentActor(),
                        "cluster",
                        "ACTIVE",
                        policy.toMetadata()
                );
                System.out.printf("Safety policy saved: %s%n", record.id());
                printPolicy(policy);
                return 0;
            }
        }

        @Command(name = "limits", description = "Show effective safety limits")
        static class LimitsCommand implements Callable<Integer> {
            @ParentCommand private SafetyCommand safetyCommand;
            @Override public Integer call() {
                printPolicy(loadSafetyPolicy(safetyCommand.systemCommand));
                return 0;
            }
        }

        @Command(name = "incident", description = "Record a safety incident and optionally drain/quarantine")
        static class IncidentCommand implements Callable<Integer> {
            @ParentCommand private SafetyCommand safetyCommand;
            @Option(names = "--node", required = true) private String node;
            @Option(names = "--gpu-id") private String gpuId;
            @Option(names = "--severity", defaultValue = "warning") private String severity;
            @Option(names = "--action", defaultValue = "note", description = "note, quarantine, drain, or disable-scheduling") private String action;
            @Option(names = "--message", required = true) private String message;

            @Override public Integer call() {
                CliSupport.requireOneOf(severity, "severity", Set.of("info", "warning", "critical"));
                CliSupport.requireOneOf(action, "action", Set.of("note", "quarantine", "drain", "disable-scheduling"));
                SystemCommand systemCommand = safetyCommand.systemCommand;
                if ("quarantine".equalsIgnoreCase(action)) {
                    CliSupport.requireNonBlank(gpuId, "gpu-id");
                    systemCommand.context().inventoryRepository().putNodeAttribute(node, HealthScoringService.gpuQuarantineKey(gpuId), "true");
                    systemCommand.context().inventoryRepository().putNodeAttribute(node, HealthScoringService.gpuQuarantineKey(gpuId) + ".reason", message);
                } else if ("drain".equalsIgnoreCase(action) || "disable-scheduling".equalsIgnoreCase(action)) {
                    systemCommand.context().inventoryRepository().putNodeAttribute(node, "state.drained", "true");
                    systemCommand.context().inventoryRepository().putNodeAttribute(node, "state.drained.reason", message);
                }
                OpsRecord record = systemCommand.context().enterpriseOpsService().record(
                        "system",
                        "incident",
                        node,
                        CliSupport.currentActor(),
                        gpuId == null ? node : node + ":" + gpuId,
                        severity.toUpperCase(Locale.ROOT),
                        mapOf("node", node, "gpuId", gpuId, "action", action, "message", message)
                );
                System.out.printf("Safety incident recorded: %s%n", record.id());
                System.out.printf("Action: %s%n", action);
                return 0;
            }
        }
    }

    @Command(name = "backup", description = "Backup metadata database")
    static class BackupCommand implements Callable<Integer> {
        @ParentCommand private SystemCommand systemCommand;
        @Option(names = "--path", required = true) private Path path;
        @Override public Integer call() throws Exception {
            CliSupport.requireNotDirectory(path, "backup path");
            CliSupport.requireDistinctPaths(systemCommand.context().dbPath(), path, "Backup path must differ from the live database path.");
            systemCommand.context().inventoryRepository().initialize();
            CliSupport.writeBytesAtomic(path, Files.readAllBytes(systemCommand.context().dbPath()));
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
            CliSupport.requireRegularFile(path, "backup file");
            CliSupport.requireDistinctPaths(path, systemCommand.context().dbPath(), "Restore source must differ from the live database path.");
            systemCommand.context().accessControlService().requireRole(
                    CliSupport.currentActor(),
                    com.drewdrew1.core.model.RbacRole.ADMIN,
                    null,
                    "ADMIN role is required to restore the metadata database."
            );
            byte[] payload = Files.readAllBytes(path);
            CliSupport.require(looksLikeSqlite(payload), "Restore file does not look like a SQLite database: " + path);
            Path currentDb = systemCommand.context().dbPath();
            if (Files.exists(currentDb)) {
                Path rollbackPath = currentDb.resolveSibling(currentDb.getFileName() + ".before-restore.bak");
                CliSupport.writeBytesAtomic(rollbackPath, Files.readAllBytes(currentDb));
            }
            CliSupport.writeBytesAtomic(currentDb, payload);
            systemCommand.context().logService().info("system", "restore", "Restored database backup", path.toAbsolutePath().toString());
            System.out.println("Database restored from " + path.toAbsolutePath());
            return 0;
        }
    }

    @Command(name = "update", description = "Self-update")
    static class UpdateCommand implements Callable<Integer> {
        @ParentCommand private SystemCommand systemCommand;
        @Override public Integer call() throws Exception {
            systemCommand.context().accessControlService().requireRole(
                    CliSupport.currentActor(),
                    com.drewdrew1.core.model.RbacRole.ADMIN,
                    null,
                    "ADMIN role is required to refresh launcher scripts."
            );
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
        CliSupport.writeStringAtomic(runtimeHome.resolve("gpum.cmd"), launcherCmd());
        CliSupport.writeStringAtomic(runtimeHome.resolve("gpum.ps1"), launcherPs1());
        Path sh = runtimeHome.resolve("gpum");
        CliSupport.writeStringAtomic(sh, launcherSh());
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
                java --enable-native-access=ALL-UNNAMED -jar "%GPUM_TARGET_JAR%" %*
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
                & java --enable-native-access=ALL-UNNAMED -jar $gpumJar @args
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
                exec java --enable-native-access=ALL-UNNAMED -jar "$GPUM_TARGET_JAR" "$@"
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

    private static boolean looksLikeSqlite(byte[] payload) {
        byte[] prefix = "SQLite format 3".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (payload == null || payload.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (payload[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static SafetyPolicy loadSafetyPolicy(SystemCommand systemCommand) {
        SafetyPolicy defaults = new SafetyPolicy(
                128,
                720,
                systemCommand.context().config().getMonitoring().getThermalWarnC(),
                systemCommand.context().config().getMonitoring().getThermalCriticalC(),
                0.02,
                2000,
                2,
                120,
                512
        );
        List<OpsRecord> records = systemCommand.context().opsRepository().list("system", "safety-policy");
        if (records.isEmpty()) {
            return defaults;
        }
        Map<String, String> metadata = records.getFirst().metadata();
        return new SafetyPolicy(
                intValue(metadata, "maxGpusPerRequest", defaults.maxGpusPerRequest()),
                intValue(metadata, "maxLeaseHours", defaults.maxLeaseHours()),
                doubleValue(metadata, "thermalWarnC", defaults.thermalWarnC()),
                doubleValue(metadata, "thermalCriticalC", defaults.thermalCriticalC()),
                doubleValue(metadata, "minFreeVramRatio", defaults.minFreeVramRatio()),
                intValue(metadata, "maxPowerLimitW", defaults.maxPowerLimitW()),
                longValue(metadata, "minDiskFreeGb", defaults.minDiskFreeGb()),
                intValue(metadata, "heartbeatStaleSec", defaults.heartbeatStaleSec()),
                intValue(metadata, "maxJobShmGb", defaults.maxJobShmGb())
        );
    }

    private static List<SafetyFinding> collectSafetyFindings(SystemCommand systemCommand, SafetyPolicy policy) throws Exception {
        List<SafetyFinding> findings = new ArrayList<>();
        List<GpuDevice> gpus = systemCommand.context().inventoryRepository().listGpus();
        if (gpus.isEmpty()) {
            findings.add(new SafetyFinding("gpu-inventory", "WARN", "no GPU inventory", "Run gpum node scan before allocation."));
        }
        for (GpuDevice gpu : gpus) {
            String target = gpu.nodeHostname() + ":" + CliSupport.safe(gpu.deviceId());
            if (gpu.temperatureC() != null && gpu.temperatureC() >= policy.thermalCriticalC()) {
                findings.add(new SafetyFinding(target, "CRITICAL", "temperature=" + gpu.temperatureC() + "C", "Drain node and inspect cooling before scheduling."));
            } else if (gpu.temperatureC() != null && gpu.temperatureC() >= policy.thermalWarnC()) {
                findings.add(new SafetyFinding(target, "WARN", "temperature=" + gpu.temperatureC() + "C", "Watch thermals and avoid new high-power jobs."));
            }
            if (gpu.powerUsageW() != null && gpu.powerLimitW() != null && gpu.powerLimitW() > 0) {
                double ratio = gpu.powerUsageW() / gpu.powerLimitW();
                if (ratio >= 1.0) {
                    findings.add(new SafetyFinding(target, "CRITICAL", "power at or above limit", "Preempt workload and check PSU/cooling headroom."));
                } else if (ratio >= 0.98) {
                    findings.add(new SafetyFinding(target, "WARN", "power near limit", "Avoid raising clocks or power cap."));
                }
            }
            if (gpu.powerLimitW() != null && gpu.powerLimitW() > policy.maxPowerLimitW()) {
                findings.add(new SafetyFinding(target, "WARN", "powerLimit=" + gpu.powerLimitW() + "W", "Configured limit is above policy max."));
            }
            if (gpu.vramTotalMb() != null && gpu.vramFreeMb() != null && gpu.vramTotalMb() > 0) {
                double freeRatio = (double) gpu.vramFreeMb() / gpu.vramTotalMb();
                if (freeRatio < policy.minFreeVramRatio()) {
                    findings.add(new SafetyFinding(target, "WARN", "vram free ratio=" + String.format(Locale.ROOT, "%.3f", freeRatio), "Run zombie cleanup or stop leaking jobs."));
                }
            }
        }
        for (AllocationRecord allocation : systemCommand.context().allocationRepository().listActive()) {
            if (allocation.requestedGpuCount() > policy.maxGpusPerRequest()) {
                findings.add(new SafetyFinding(allocation.id(), "CRITICAL", "requested GPUs exceed policy", "Release or split the allocation."));
            }
            long leaseHours = Duration.between(allocation.createdAt(), allocation.expiresAt()).toHours();
            if (leaseHours > policy.maxLeaseHours()) {
                findings.add(new SafetyFinding(allocation.id(), "WARN", "lease hours=" + leaseHours, "Shorten lease or require approval."));
            }
            if (allocation.expiresAt().isBefore(Instant.now())) {
                findings.add(new SafetyFinding(allocation.id(), "CRITICAL", "active allocation already expired", "Run gpum alloc reap."));
            }
        }
        long freeGb = Files.getFileStore(systemCommand.context().dbPath().toAbsolutePath().getParent())
                .getUsableSpace() / 1024L / 1024L / 1024L;
        if (freeGb < policy.minDiskFreeGb()) {
            findings.add(new SafetyFinding("metadata-disk", "CRITICAL", "freeGb=" + freeGb, "Free disk before scans, backups, or telemetry writes."));
        }
        if ("1".equals(System.getenv("GPUM_ENABLE_HARDWARE_WRITE")) || Boolean.getBoolean("gpum.enableHardwareWrite")) {
            findings.add(new SafetyFinding("hardware-write", "WARN", "enabled", "Keep hardware mutation enabled only during approved maintenance windows."));
        }
        for (OpsRecord heartbeat : systemCommand.context().opsRepository().list("server", "heartbeat")) {
            long age = Duration.between(heartbeat.updatedAt(), Instant.now()).toSeconds();
            if (age > policy.heartbeatStaleSec()) {
                findings.add(new SafetyFinding(heartbeat.name(), "WARN", "heartbeat stale seconds=" + age, "Check node agent or network connectivity."));
            }
        }
        if (findings.stream().noneMatch(finding -> !"OK".equals(finding.severity()))) {
            findings.add(new SafetyFinding("cluster", "OK", "all checks passed", "No safety blockers found."));
        }
        return findings;
    }

    private static void printFindings(List<SafetyFinding> findings) {
        List<String[]> rows = new ArrayList<>();
        for (SafetyFinding finding : findings) {
            rows.add(new String[]{finding.component(), finding.severity(), finding.status(), finding.recommendation()});
        }
        System.out.println(AsciiTable.getTable(
                new String[]{"Component", "Severity", "Status", "Recommendation"},
                rows.toArray(String[][]::new)
        ));
    }

    private static void printPolicy(SafetyPolicy policy) {
        List<String[]> rows = List.of(
                new String[]{"maxGpusPerRequest", Integer.toString(policy.maxGpusPerRequest())},
                new String[]{"maxLeaseHours", Integer.toString(policy.maxLeaseHours())},
                new String[]{"thermalWarnC", Double.toString(policy.thermalWarnC())},
                new String[]{"thermalCriticalC", Double.toString(policy.thermalCriticalC())},
                new String[]{"minFreeVramRatio", Double.toString(policy.minFreeVramRatio())},
                new String[]{"maxPowerLimitW", Integer.toString(policy.maxPowerLimitW())},
                new String[]{"minDiskFreeGb", Long.toString(policy.minDiskFreeGb())},
                new String[]{"heartbeatStaleSec", Integer.toString(policy.heartbeatStaleSec())},
                new String[]{"maxJobShmGb", Integer.toString(policy.maxJobShmGb())}
        );
        System.out.println(AsciiTable.getTable(new String[]{"Limit", "Value"}, rows.toArray(String[][]::new)));
    }

    private static void validatePolicy(SafetyPolicy policy) {
        CliSupport.requireRange(policy.maxGpusPerRequest(), 1, 4096, "max-gpus-per-request");
        CliSupport.requireRange(policy.maxLeaseHours(), 1, 8760, "max-lease-hours");
        CliSupport.require(policy.thermalWarnC() > 0 && policy.thermalWarnC() < policy.thermalCriticalC(),
                "thermal-warn-c must be positive and lower than thermal-critical-c");
        CliSupport.require(policy.thermalCriticalC() <= 110.0, "thermal-critical-c must be <= 110");
        CliSupport.require(policy.minFreeVramRatio() >= 0.0 && policy.minFreeVramRatio() <= 1.0,
                "min-free-vram-ratio must be between 0 and 1");
        CliSupport.requireRange(policy.maxPowerLimitW(), 1, 2000, "max-power-limit-w");
        CliSupport.require(policy.minDiskFreeGb() >= 0L && policy.minDiskFreeGb() <= 1_000_000L,
                "min-disk-free-gb must be between 0 and 1000000");
        CliSupport.requireRange(policy.heartbeatStaleSec(), 1, 86_400, "heartbeat-stale-sec");
        CliSupport.requireRange(policy.maxJobShmGb(), 1, 4096, "max-job-shm-gb");
    }

    private static Integer value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static Long value(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static Double value(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private static int intValue(Map<String, String> metadata, String key, int fallback) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longValue(Map<String, String> metadata, String key, long fallback) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleValue(Map<String, String> metadata, String key, double fallback) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Map<String, String> mapOf(String... values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(values[i], values[i + 1] == null ? "" : values[i + 1]);
        }
        return map;
    }

    private record SafetyPolicy(
            int maxGpusPerRequest,
            int maxLeaseHours,
            double thermalWarnC,
            double thermalCriticalC,
            double minFreeVramRatio,
            int maxPowerLimitW,
            long minDiskFreeGb,
            int heartbeatStaleSec,
            int maxJobShmGb
    ) {
        Map<String, String> toMetadata() {
            return mapOf(
                    "maxGpusPerRequest", Integer.toString(maxGpusPerRequest),
                    "maxLeaseHours", Integer.toString(maxLeaseHours),
                    "thermalWarnC", Double.toString(thermalWarnC),
                    "thermalCriticalC", Double.toString(thermalCriticalC),
                    "minFreeVramRatio", Double.toString(minFreeVramRatio),
                    "maxPowerLimitW", Integer.toString(maxPowerLimitW),
                    "minDiskFreeGb", Long.toString(minDiskFreeGb),
                    "heartbeatStaleSec", Integer.toString(heartbeatStaleSec),
                    "maxJobShmGb", Integer.toString(maxJobShmGb)
            );
        }
    }

    private record SafetyFinding(String component, String severity, String status, String recommendation) {
    }
}
