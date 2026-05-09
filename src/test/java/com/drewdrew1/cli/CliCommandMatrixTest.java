package com.drewdrew1.cli;

import com.drewdrew1.App;
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
import java.util.ArrayList;
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
    void everyRegisteredCommandPrintsHelp() {
        assertHelpTree(App.createCommandLine(), new ArrayList<>());
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
        assertEquals(0, execute("runtime", "zombie", "clean", "--gpu-id", "0").exitCode());
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
    void executesEnterpriseOpsCommandsAgainstFixtureInventory() {
        CommandCapture create = execute("alloc", "request", "--gpus", "1", "--vram", "60000", "--hours", "2", "--dry-run");
        assertEquals(0, create.exitCode());
        CommandCapture allocation = execute("alloc", "request", "--gpus", "1", "--vram", "60000", "--hours", "2");
        assertEquals(0, allocation.exitCode());
        AllocationRecord record = new SqliteAllocationRepository(dbPath).listActive().getFirst();

        assertEquals(0, execute("compute", "quota", "--allocation-id", record.id(), "--cpu-cores", "4", "--memory-mb", "32000", "--pids", "256").exitCode());
        assertEquals(0, execute("compute", "model-quota", "--name", "team-a-v100", "--tenant", "team-a", "--gpu-model", "V100", "--max-gpus", "8").exitCode());
        assertEquals(0, execute("compute", "rdma", "--name", "ib-fast", "--node", "nvidia-h-pool", "--device", "ib0", "--bandwidth-mbit", "100000", "--priority", "1").exitCode());
        assertEquals(0, execute("compute", "accelerator", "register", "--name", "edge-npu", "--kind", "npu", "--driver", "vendor-npu", "--endpoint", "node-a:/dev/npu0").exitCode());
        CommandCapture accelerators = execute("compute", "accelerator", "list");
        assertEquals(0, accelerators.exitCode());
        assertTrue(accelerators.stdout().contains("edge-npu"));

        assertEquals(0, execute("schedule", "queue", "create", "--name", "research", "--tenant", "research", "--weight", "10", "--max-gpus", "8", "--preemptible").exitCode());
        assertEquals(0, execute("schedule", "reserve", "create", "--name", "nightly", "--queue", "research", "--start", "2030-01-01T00:00:00Z", "--end", "2030-01-01T02:00:00Z", "--gpus", "4").exitCode());
        assertEquals(0, execute("schedule", "fair-share", "--owner", System.getProperty("user.name", "unknown"), "--window-hours", "24").exitCode());
        assertEquals(0, execute("schedule", "gang", "--name", "ddp", "--nodes", "2", "--gpus-per-node", "8").exitCode());
        assertEquals(0, execute("schedule", "preempt", "--name", "urgent", "--victim-allocation-id", record.id(), "--incoming", "priority-train").exitCode());
        assertEquals(0, execute("schedule", "place", "--gpus", "1", "--strategy", "best-fit").exitCode());
        assertEquals(0, execute("schedule", "place", "--gpus", "1", "--strategy", "worst-fit").exitCode());
        assertEquals(0, execute("schedule", "place", "--gpus", "1", "--strategy", "topology").exitCode());
        assertEquals(0, execute("schedule", "backfill", "--queue", "research", "--max-minutes", "60", "--max-gpus", "4").exitCode());

        assertEquals(0, execute("data", "cache", "--name", "imagenet", "--source", tempDir.toString(), "--target", tempDir.resolve("cache").toString()).exitCode());
        assertEquals(0, execute("data", "snapshot", "--name", "imagenet-v1", "--source", "s3://datasets/imagenet", "--version", "v1", "--mount", tempDir.resolve("snap").toString()).exitCode());
        assertEquals(0, execute("data", "checkpoint", "--name", "ckpt", "--source", tempDir.toString(), "--dest", tempDir.resolve("ckpt-copy").toString()).exitCode());
        assertEquals(0, execute("data", "gds", "--name", "gds-datasets", "--mount", tempDir.resolve("gds").toString()).exitCode());

        assertEquals(0, execute("job", "batch", "--name", "train", "--allocation-id", record.id(), "--command", "echo train").exitCode());
        assertEquals(0, execute("job", "batch", "--name", "train-apptainer", "--allocation-id", record.id(), "--image", "train.sif", "--engine", "apptainer", "--command", "python train.py").exitCode());
        assertEquals(0, execute("job", "session", "--name", "lab", "--allocation-id", record.id(), "--kind", "jupyter", "--port", "8899").exitCode());
        assertEquals(0, execute("job", "list").exitCode());

        assertEquals(0, execute("observe", "alert", "create", "--name", "slack-done", "--channel", "slack", "--target", "https://hooks.example", "--event", "job.done").exitCode());
        assertEquals(0, execute("observe", "profile", "--name", "profile-train", "--allocation-id", record.id(), "--tool", "shell", "--command", "echo profile").exitCode());
        assertEquals(0, execute("observe", "telemetry", "--name", "fast", "--interval-sec", "5", "--retention-hours", "24").exitCode());
        assertEquals(0, execute("observe", "log-stream", "--lines", "5").exitCode());
        assertEquals(0, execute("report", "budget", "--name", "budget", "--owner", System.getProperty("user.name", "unknown"), "--budget", "100", "--rate-per-gpu-hour", "2").exitCode());
        assertEquals(0, execute("report", "cost-estimate", "--owner", System.getProperty("user.name", "unknown"), "--gpu-model", "H100", "--gpus", "2", "--hours", "4", "--rate-per-gpu-hour", "3.5").exitCode());

        CommandCapture secretPut = execute("secret", "put", "--name", "wandb", "--provider", "env", "--ref", "WANDB_API_KEY", "--env", "WANDB_API_KEY");
        assertEquals(0, secretPut.exitCode());
        CommandCapture secretList = execute("secret", "list");
        assertEquals(0, secretList.exitCode());
        String secretId = extractId(secretList.stdout(), "ref-");
        assertEquals(0, execute("secret", "render", "--id", secretId, "--format", "shell").exitCode());

        assertEquals(0, execute("dev", "completion", "--shell", "bash").exitCode());
        assertEquals(0, execute("dev", "native").exitCode());
        assertEquals(0, execute("dev", "terminal").exitCode());
        assertEquals(0, execute("dev", "python-sdk", "--output", tempDir.resolve("gpum_client.py").toString()).exitCode());
        assertTrue(Files.exists(tempDir.resolve("gpum_client.py")));

        assertEquals(0, execute("system", "safety", "limits").exitCode());
        assertEquals(0, execute("system", "safety", "policy",
                "--max-gpus-per-request", "2",
                "--max-lease-hours", "48",
                "--thermal-warn-c", "70",
                "--thermal-critical-c", "85",
                "--min-free-vram-ratio", "0.05",
                "--max-power-limit-w", "900",
                "--min-disk-free-gb", "0",
                "--heartbeat-stale-sec", "300",
                "--max-job-shm-gb", "64").exitCode());
        assertEquals(0, execute("system", "safety", "check").exitCode());
        assertEquals(0, execute("system", "safety", "incident",
                "--node", "nvidia-h-pool",
                "--gpu-id", "0",
                "--severity", "warning",
                "--action", "quarantine",
                "--message", "matrix safety incident").exitCode());
        CommandCapture overLimit = execute("alloc", "request", "--gpus", "3", "--vram", "60000", "--hours", "2", "--dry-run");
        assertTrue(overLimit.exitCode() != 0);
        assertTrue(overLimit.stderr().contains("gpus must be between"));

        assertEquals(0, execute("server", "storage").exitCode());
        assertEquals(0, execute("server", "run", "--port", "0", "--once").exitCode());
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
            int exitCode = App.createCommandLine().execute(fullArgs);
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

    private void assertHelpTree(CommandLine commandLine, ArrayList<String> path) {
        ArrayList<String> args = new ArrayList<>(path);
        args.add("--help");
        CommandCapture capture = execute(args.toArray(String[]::new));
        assertEquals(0, capture.exitCode(), "help failed for " + String.join(" ", path) + "\n" + capture.stderr());
        for (var entry : commandLine.getSubcommands().entrySet()) {
            if ("help".equals(entry.getKey())) {
                continue;
            }
            ArrayList<String> childPath = new ArrayList<>(path);
            childPath.add(entry.getKey());
            assertHelpTree(entry.getValue(), childPath);
        }
    }
}
