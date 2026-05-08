package com.drewdrew1.cli;

import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.infra.persistence.SqliteAllocationRepository;
import com.drewdrew1.infra.persistence.SqliteInventoryRepository;
import com.drewdrew1.testsupport.TestInventoryFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes the CLI command surface against fixture data to catch wiring regressions. */
class CliCommandMatrixTest {
    @TempDir
    Path tempDir;

    private Path dbPath;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("gpum-cli.db");
        SqliteInventoryRepository inventoryRepository = new SqliteInventoryRepository(dbPath);
        TestInventoryFixtures.seedMixedFleet(inventoryRepository);
    }

    @Test
    void executesNodeAndGpuCommandsAgainstFixtureInventory() {
        String currentUser = System.getProperty("user.name", "unknown");
        assertEquals(0, execute("rbac", "role", "grant", "--actor", currentUser, "--role", "admin").exitCode());
        assertEquals(0, execute("rbac", "role", "grant", "--actor", currentUser, "--role", "operator").exitCode());
        CommandCapture nodeList = execute("node", "list", "--sort", "gpu");
        assertEquals(0, nodeList.exitCode());
        assertTrue(nodeList.stdout().contains("nvidia-h-pool"));

        CommandCapture nodeInfo = execute("node", "info", "nvidia-h-pool");
        assertEquals(0, nodeInfo.exitCode());
        assertTrue(nodeInfo.stdout().contains("GPU inventory"));

        assertEquals(0, execute("node", "top", "--metric", "util").exitCode());
        assertEquals(0, execute("node", "maintenance", "nvidia-h-pool", "--on", "--reason", "fixture").exitCode());
        assertEquals(0, execute("node", "maintenance", "nvidia-h-pool", "--off").exitCode());
        assertEquals(0, execute("node", "label", "nvidia-h-pool", "--set", "zone=lab,team=platform").exitCode());
        CommandCapture labelShow = execute("node", "label", "nvidia-h-pool", "--show");
        assertEquals(0, labelShow.exitCode());
        assertTrue(labelShow.stdout().contains("zone=lab"));
        assertEquals(0, execute("node", "label", "nvidia-h-pool", "--remove", "zone,team").exitCode());
        assertEquals(0, execute("node", "drain", "nvidia-h-pool", "--graceful", "--timeout", "5", "--reason", "patching", "--evict").exitCode());
        assertEquals(0, execute("node", "undrain", "nvidia-h-pool").exitCode());
        assertEquals(0, execute("node", "remote", "add", "--ip", "10.0.0.10", "--ssh-user", "gpuadmin", "--alias", "lab-a").exitCode());
        CommandCapture remoteList = execute("node", "remote", "list");
        assertEquals(0, remoteList.exitCode());
        assertTrue(remoteList.stdout().contains("lab-a"));
        assertEquals(0, execute("node", "remote", "remove", "--ip", "10.0.0.10").exitCode());

        CommandCapture gpuList = execute("gpu", "list", "--min-vram", "80000", "--capability", "mig");
        assertEquals(0, gpuList.exitCode());
        assertTrue(gpuList.stdout().contains("H100"));
        assertEquals(0, execute("gpu", "stats", "--json").exitCode());
        assertEquals(0, execute("gpu", "stats", "--export", "csv").exitCode());
        assertTrue(Files.exists(tempDir.resolve("gpu-stats-export.csv")));
        assertEquals(0, execute("gpu", "health", "--check-ecc", "--thermal-test", "--memory-test", "--report").exitCode());
        assertEquals(0, execute("gpu", "health", "--score", "--quarantine-threshold", "10").exitCode());
        assertEquals(0, execute("gpu", "set", "--id", "0", "--power-limit", "300").exitCode());
        assertEquals(0, execute("gpu", "reset", "--id", "0", "--soft", "--drain-first").exitCode());
        CommandCapture topology = execute("gpu", "topology", "--visualize");
        assertEquals(0, topology.exitCode());
        assertTrue(topology.stdout().contains("Node: nvidia-h-pool"));
    }

    @Test
    void executesAllocationAuditAndSystemCommandsAgainstFixtureInventory() {
        String currentUser = System.getProperty("user.name", "unknown");
        assertEquals(0, execute("rbac", "role", "grant", "--actor", currentUser, "--role", "admin").exitCode());
        assertEquals(0, execute("rbac", "role", "grant", "--actor", currentUser, "--role", "operator").exitCode());
        CommandCapture dryRun = execute("alloc", "request", "--gpus", "1", "--vram", "60000", "--hours", "2", "--label-selector", "role=trainer", "--dry-run");
        assertEquals(0, dryRun.exitCode());
        assertTrue(dryRun.stdout().contains("Dry-run allocation candidate"));

        CommandCapture create = execute("alloc", "request", "--gpus", "1", "--vram", "60000", "--hours", "2", "--label-selector", "role=trainer");
        assertEquals(0, create.exitCode());
        assertTrue(create.stdout().contains("Created allocation"));

        SqliteAllocationRepository allocationRepository = new SqliteAllocationRepository(dbPath);
        AllocationRecord record = allocationRepository.listActive().getFirst();

        CommandCapture list = execute("alloc", "list", "--mine", "--status", "active");
        assertEquals(0, list.exitCode());
        assertTrue(list.stdout().contains(record.id()));

        CommandCapture info = execute("alloc", "info", "--id", record.id());
        assertEquals(0, info.exitCode());
        assertTrue(info.stdout().contains("Environment:"));
        CommandCapture estimate = execute("alloc", "estimate", "--model", "llama", "--params-b", "7", "--precision", "fp16", "--context", "4096", "--batch", "1");
        assertEquals(0, estimate.exitCode());
        assertTrue(estimate.stdout().contains("Recommended VRAM MB"));
        assertEquals(0, execute("integration", "ai", "env", "--allocation-id", record.id(), "--format", "json").exitCode());
        assertEquals(0, execute("integration", "ai", "preset", "render", "--allocation-id", record.id(), "--name", "torchrun-ddp", "--entrypoint", "train.py").exitCode());
        assertEquals(0, execute("integration", "k8s", "submit", "--name", "trainer", "--image", "repo/train:latest", "--allocation-id", record.id()).exitCode());
        assertEquals(0, execute("runtime", "native", "metrics").exitCode());
        assertEquals(0, execute("runtime", "worker", "register", "--id", "worker-matrix", "--allocation-id", record.id(), "--command", "echo ok",
                "--checkpoint-command", "echo checkpoint", "--restore-command", "echo restore").exitCode());
        assertEquals(0, execute("runtime", "worker", "list").exitCode());
        assertEquals(0, execute("runtime", "worker", "events", "--id", "worker-matrix").exitCode());
        assertEquals(0, execute("runtime", "migrate", "plan", "--worker-id", "worker-matrix", "--to-node", "amd-mi-pool").exitCode());
        assertEquals(0, execute("runtime", "oom", "handle", "--allocation-id", record.id(), "--strategy", "restart").exitCode());

        assertEquals(0, execute("alloc", "extend", "--id", record.id(), "--hours", "2", "--reason", "integration-test").exitCode());
        assertEquals(0, execute("alloc", "move", "--id", record.id(), "--to-node", "amd-mi-pool").exitCode());
        AllocationRecord movedRecord = allocationRepository.listActive().getFirst();
        assertEquals(0, execute("alloc", "release", "--id", movedRecord.id(), "--kill-process").exitCode());
        assertEquals(0, execute("alloc", "reap").exitCode());

        CommandCapture auditList = execute("audit", "list", "--tail", "20");
        assertEquals(0, auditList.exitCode());
        assertTrue(auditList.stdout().contains("ALLOC_CREATE"));

        CommandCapture auditTrace = execute("audit", "trace", record.id());
        assertEquals(0, auditTrace.exitCode());
        assertTrue(auditTrace.stdout().contains("ALLOC_CREATE"));

        assertEquals(0, execute("log", "write", "--level", "info", "--component", "test", "--category", "integration", "--message", "hello", "--context", "matrix").exitCode());
        CommandCapture logList = execute("log", "list", "--component", "test", "--contains", "hello", "--sort", "desc", "--limit", "10");
        assertEquals(0, logList.exitCode());
        assertTrue(logList.stdout().contains("hello"));
        assertEquals(0, execute("log", "tail", "--lines", "5").exitCode());
        assertEquals(0, execute("integration", "mlflow", "status").exitCode());

        assertEquals(0, execute("system", "config", "--show-defaults").exitCode());
        assertEquals(0, execute("system", "config", "--reload").exitCode());

        CommandCapture dbCheck = execute("system", "db-check", "--repair", "--vacuum", "--orphan-clean");
        assertEquals(0, dbCheck.exitCode());
        assertTrue(dbCheck.stdout().contains("integrity_check=ok"));

        assertEquals(0, execute("system", "health").exitCode());

        Path backupPath = tempDir.resolve("backup").resolve("gpu-mgr-backup.db");
        assertEquals(0, execute("system", "backup", "--path", backupPath.toString()).exitCode());
        assertTrue(Files.exists(backupPath));
        assertEquals(0, execute("system", "restore", "--path", backupPath.toString()).exitCode());
        assertEquals(0, execute("system", "update").exitCode());
    }

    @Test
    void executesGovernanceAndPartitionCommands() throws Exception {
        Path rateCard = tempDir.resolve("rate-card.yaml");
        Files.writeString(rateCard, "default_gpu_hour: 1.0\nmodels:\n  H100: 4.0\n", StandardCharsets.UTF_8);
        String currentUser = System.getProperty("user.name", "unknown");
        assertEquals(0, execute("rbac", "role", "grant", "--actor", currentUser, "--role", "admin").exitCode());
        assertEquals(0, execute("rbac", "role", "grant", "--actor", currentUser, "--role", "operator").exitCode());

        CommandCapture partCreate = execute("part", "create", "--gpu", "nvidia-h-pool:0", "--profile", "1g.10gb", "--count", "1");
        assertEquals(0, partCreate.exitCode());
        CommandCapture partList = execute("part", "list");
        assertEquals(0, partList.exitCode());
        String partitionId = extractId(partList.stdout(), "part-");
        assertEquals(0, execute("part", "destroy", "--id", partitionId).exitCode());
        assertEquals(0, execute("part", "auto-optimize").exitCode());

        assertEquals(0, execute("rbac", "whoami").exitCode());
        assertEquals(0, execute("quota", "set", "--name", currentUser, "--max-gpus", "1", "--max-vram", "320000", "--max-lease-hours", "72").exitCode());
        assertEquals(0, execute("quota", "alert", "--name", currentUser, "--threshold", "80,90").exitCode());
        assertEquals(0, execute("quota", "status", "--user", currentUser, "--remaining").exitCode());

        CommandCapture queuedAlloc = execute("alloc", "request", "--gpus", "2", "--vram", "60000", "--hours", "2", "--label-selector", "role=trainer");
        assertEquals(0, queuedAlloc.exitCode());
        assertTrue(queuedAlloc.stdout().contains("queued as"));

        CommandCapture queueList = execute("queue", "list", "--full", "--position", "my", "--estimate");
        assertEquals(0, queueList.exitCode());
        String queueId = extractId(queueList.stdout(), "queue-");
        assertEquals(0, execute("queue", "promote", "--id", queueId, "--val", "3").exitCode());
        assertEquals(0, execute("queue", "demote", "--id", queueId, "--val", "2").exitCode());

        assertEquals(0, execute("report", "usage", "--format", "json", "--by", "model").exitCode());
        assertEquals(0, execute("report", "billing", "--rate-card", rateCard.toString()).exitCode());
        CommandCapture prometheus = execute("report", "prometheus");
        assertEquals(0, prometheus.exitCode());
        assertTrue(prometheus.stdout().contains("gpum_gpu_health_score"));
    }

    @Test
    void executesLocalNodeScanCommandsWithoutAssumingGpuHardware() {
        Path isolatedDb = tempDir.resolve("scan-only.db");

        CommandCapture scan = executeAgainst(isolatedDb, "node", "scan", "--force", "--discovery-depth", "1");
        assertEquals(0, scan.exitCode());
        assertTrue(scan.stdout().contains("Scanned node"));

        CommandCapture scanAll = executeAgainst(isolatedDb, "node", "scan", "--all", "--discovery-depth", "1");
        assertEquals(0, scanAll.exitCode());
        assertTrue(scanAll.stdout().contains("Scanned"));
    }

    private CommandCapture execute(String... args) {
        return executeAgainst(dbPath, args);
    }

    private CommandCapture executeAgainst(Path targetDb, String... args) {
        String[] fullArgs = new String[args.length + 2];
        fullArgs[0] = "--db";
        fullArgs[1] = targetDb.toString();
        System.arraycopy(args, 0, fullArgs, 2, args.length);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            int exitCode = new CommandLine(new GpuMgrCommand()).execute(fullArgs);
            out.flush();
            err.flush();
            return new CommandCapture(
                    exitCode,
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8)
            );
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /** Captures the exit code and console output of one CLI execution. */
    private record CommandCapture(int exitCode, String stdout, String stderr) {
    }

    private String extractId(String text, String prefix) {
        Matcher matcher = Pattern.compile(Pattern.quote(prefix) + "[A-Za-z0-9\\-]+").matcher(text);
        assertTrue(matcher.find(), "Expected id with prefix " + prefix + " in output:\n" + text);
        return matcher.group();
    }
}
