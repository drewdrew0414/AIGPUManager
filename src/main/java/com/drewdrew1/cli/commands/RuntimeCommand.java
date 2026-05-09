package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.AppContext;
import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.NativeGpuMetric;
import com.drewdrew1.core.model.RuntimeEvent;
import com.drewdrew1.core.model.RuntimeWorkerRecord;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.service.ContainerReconcileService;
import com.drewdrew1.core.service.GpuProcessService;
import com.drewdrew1.core.service.RuntimeWorkerService;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/** Exposes native telemetry, worker lifecycle, OOM recovery, reconcile, and migration commands. */
@Command(
        name = "runtime",
        mixinStandardHelpOptions = true,
        description = "Worker daemon and runtime safety operations",
        subcommands = {
                RuntimeCommand.NativeCommand.class,
                RuntimeCommand.WorkerCommand.class,
                RuntimeCommand.DaemonCommand.class,
                RuntimeCommand.OomCommand.class,
                RuntimeCommand.ReconcileCommand.class,
                RuntimeCommand.MigrateCommand.class,
                RuntimeCommand.ZombieCommand.class
        }
)
public class RuntimeCommand implements Runnable {
    @ParentCommand
    private GpuMgrCommand parent;

    @Spec
    private CommandSpec spec;

    AppContext context() {
        return parent.createContext();
    }

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    @Command(
            name = "native",
            description = "Read optional native NVML and Level Zero telemetry",
            subcommands = {
                    NativeCommand.MetricsCommand.class
            }
    )
    static class NativeCommand implements Runnable {
        @ParentCommand
        private RuntimeCommand runtimeCommand;

        @Spec
        private CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(System.out);
        }

        @Command(name = "metrics", description = "Collect direct native GPU metrics when native libraries are installed")
        static class MetricsCommand implements Callable<Integer> {
            @ParentCommand
            private NativeCommand nativeCommand;

            @Override
            public Integer call() {
                List<NativeGpuMetric> metrics = nativeCommand.runtimeCommand.context().nativeGpuTelemetryService().collect();
                List<String[]> rows = new ArrayList<>();
                for (NativeGpuMetric metric : metrics) {
                    rows.add(new String[]{
                            metric.vendor().name(),
                            Integer.toString(metric.index()),
                            CliSupport.safe(metric.name()),
                            metric.available() ? "yes" : "no",
                            formatLong(metric.memoryTotalMb()),
                            formatLong(metric.memoryUsedMb()),
                            formatDouble(metric.utilizationGpu()),
                            CliSupport.safe(metric.source()),
                            CliSupport.safe(metric.warning())
                    });
                }
                System.out.println(AsciiTable.getTable(
                        new String[]{"Vendor", "Index", "Name", "Native", "MemMB", "UsedMB", "Util%", "Source", "Warning"},
                        rows.toArray(String[][]::new)
                ));
                return 0;
            }
        }
    }

    @Command(
            name = "worker",
            description = "Register and control AI worker processes",
            subcommands = {
                    WorkerCommand.RegisterCommand.class,
                    WorkerCommand.ListCommand.class,
                    WorkerCommand.StartCommand.class,
                    WorkerCommand.StopCommand.class,
                    WorkerCommand.RestartCommand.class,
                    WorkerCommand.RecycleCommand.class,
                    WorkerCommand.EventsCommand.class
            }
    )
    static class WorkerCommand implements Runnable {
        @ParentCommand
        private RuntimeCommand runtimeCommand;

        @Spec
        private CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(System.out);
        }

        @Command(name = "register", description = "Register a worker command without starting it")
        static class RegisterCommand implements Callable<Integer> {
            @ParentCommand
            private WorkerCommand workerCommand;

            @Option(names = "--id", description = "Worker id")
            private String id;

            @Option(names = "--allocation-id", description = "Associated allocation id")
            private String allocationId;

            @Option(names = "--tenant", description = "Tenant name")
            private String tenant;

            @Option(names = "--owner", description = "Owner actor")
            private String owner;

            @Option(names = "--command", required = true, description = "Shell command to run")
            private String command;

            @Option(names = "--cwd", description = "Working directory")
            private String workingDirectory;

            @Option(names = "--env", split = ",", description = "Environment pairs, repeated or comma separated: K=V")
            private List<String> env = new ArrayList<>();

            @Option(names = "--checkpoint-command", description = "Command used before migration")
            private String checkpointCommand;

            @Option(names = "--restore-command", description = "Command used after migration")
            private String restoreCommand;

            @Option(names = "--oom-recovery-command", description = "Command used by OOM strategy defrag")
            private String oomRecoveryCommand;

            @Option(names = "--max-restarts", defaultValue = "3")
            private int maxRestarts;

            @Option(names = "--max-lifetime-min", defaultValue = "1440")
            private int maxLifetimeMin;

            @Option(names = "--memory-restart-mb", description = "Memory threshold for external watchdog integrations")
            private Long memoryRestartMb;

            @Override
            public Integer call() {
                CliSupport.requireNonBlank(command, "command");
                CliSupport.requireRange(maxRestarts, 0, 100, "max-restarts");
                CliSupport.requireRange(maxLifetimeMin, 1, 525600, "max-lifetime-min");
                if (memoryRestartMb != null) {
                    CliSupport.requirePositiveLong(memoryRestartMb, "memory-restart-mb");
                }
                RuntimeWorkerRecord record = workerCommand.runtimeCommand.context().runtimeWorkerService().register(
                        id,
                        allocationId,
                        tenant,
                        owner == null || owner.isBlank() ? CliSupport.currentActor() : owner,
                        command,
                        workingDirectory,
                        CliSupport.parseLabels(CliSupport.copyStrings(env)),
                        checkpointCommand,
                        restoreCommand,
                        oomRecoveryCommand,
                        maxRestarts,
                        maxLifetimeMin,
                        memoryRestartMb
                );
                System.out.printf("Registered runtime worker %s.%n", record.id());
                return 0;
            }
        }

        @Command(name = "list", description = "List registered runtime workers")
        static class ListCommand implements Callable<Integer> {
            @ParentCommand
            private WorkerCommand workerCommand;

            @Override
            public Integer call() {
                printWorkers(workerCommand.runtimeCommand.context().runtimeWorkerService().list());
                return 0;
            }
        }

        @Command(name = "start", description = "Start a registered worker")
        static class StartCommand implements Callable<Integer> {
            @ParentCommand
            private WorkerCommand workerCommand;

            @Option(names = "--id", required = true)
            private String id;

            @Override
            public Integer call() {
                RuntimeWorkerRecord record = workerCommand.runtimeCommand.context().runtimeWorkerService().start(id);
                System.out.printf("Worker %s is %s pid=%s.%n", record.id(), record.status(), record.pid());
                return 0;
            }
        }

        @Command(name = "stop", description = "Stop a registered worker")
        static class StopCommand implements Callable<Integer> {
            @ParentCommand
            private WorkerCommand workerCommand;

            @Option(names = "--id", required = true)
            private String id;

            @Option(names = "--force", description = "Forcibly terminate if graceful stop does not work")
            private boolean force;

            @Override
            public Integer call() {
                RuntimeWorkerRecord record = workerCommand.runtimeCommand.context().runtimeWorkerService().stop(id, force);
                System.out.printf("Worker %s is %s.%n", record.id(), record.status());
                return 0;
            }
        }

        @Command(name = "restart", description = "Restart a registered worker within its restart budget")
        static class RestartCommand implements Callable<Integer> {
            @ParentCommand
            private WorkerCommand workerCommand;

            @Option(names = "--id", required = true)
            private String id;

            @Option(names = "--force")
            private boolean force;

            @Option(names = "--reason", defaultValue = "manual")
            private String reason;

            @Override
            public Integer call() {
                RuntimeWorkerRecord record = workerCommand.runtimeCommand.context().runtimeWorkerService().restart(id, force, reason);
                System.out.printf("Worker %s restarted pid=%s count=%d.%n", record.id(), record.pid(), record.restartCount());
                return 0;
            }
        }

        @Command(name = "recycle", description = "Preview or execute max-lifetime worker recycle")
        static class RecycleCommand implements Callable<Integer> {
            @ParentCommand
            private WorkerCommand workerCommand;

            @Option(names = "--execute", description = "Actually restart due workers")
            private boolean execute;

            @Override
            public Integer call() {
                List<RuntimeWorkerRecord> records = workerCommand.runtimeCommand.context().runtimeWorkerService().recycleDueWorkers(execute);
                if (records.isEmpty()) {
                    System.out.println("No workers are due for recycle.");
                    return 0;
                }
                System.out.println(execute ? "Recycled workers:" : "Workers due for recycle:");
                printWorkers(records);
                return 0;
            }
        }

        @Command(name = "events", description = "List runtime worker events")
        static class EventsCommand implements Callable<Integer> {
            @ParentCommand
            private WorkerCommand workerCommand;

            @Option(names = "--id")
            private String id;

            @Option(names = "--limit", defaultValue = "50")
            private int limit;

            @Override
            public Integer call() {
                CliSupport.requireRange(limit, 1, 1000, "limit");
                List<RuntimeEvent> events = workerCommand.runtimeCommand.context().runtimeWorkerService().listEvents(id, limit);
                List<String[]> rows = new ArrayList<>();
                for (RuntimeEvent event : events) {
                    rows.add(new String[]{
                            event.createdAt().toString(),
                            CliSupport.safe(event.workerId()),
                            CliSupport.safe(event.eventType()),
                            CliSupport.safe(event.message())
                    });
                }
                System.out.println(AsciiTable.getTable(
                        new String[]{"Created", "Worker", "Type", "Message"},
                        rows.toArray(String[][]::new)
                ));
                return 0;
            }
        }

        private static void printWorkers(List<RuntimeWorkerRecord> workers) {
            List<String[]> rows = new ArrayList<>();
            for (RuntimeWorkerRecord worker : workers) {
                rows.add(new String[]{
                        worker.id(),
                        CliSupport.safe(worker.allocationId()),
                        CliSupport.safe(worker.tenant()),
                        CliSupport.safe(worker.owner()),
                        worker.status(),
                        worker.pid() == null ? "-" : worker.pid().toString(),
                        Integer.toString(worker.restartCount()),
                        Integer.toString(worker.maxRestarts()),
                        Integer.toString(worker.maxLifetimeMinutes()),
                        CliSupport.safe(worker.command())
                });
            }
            System.out.println(AsciiTable.getTable(
                    new String[]{"Id", "Allocation", "Tenant", "Owner", "Status", "PID", "Restarts", "Max", "LifeMin", "Command"},
                    rows.toArray(String[][]::new)
            ));
        }
    }

    @Command(
            name = "daemon",
            description = "Run runtime watchdog ticks",
            subcommands = {
                    DaemonCommand.RunCommand.class
            }
    )
    static class DaemonCommand implements Runnable {
        @ParentCommand
        private RuntimeCommand runtimeCommand;

        @Spec
        private CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(System.out);
        }

        @Command(name = "run", description = "Run worker recycle watchdog ticks")
        static class RunCommand implements Callable<Integer> {
            @ParentCommand
            private DaemonCommand daemonCommand;

            @Option(names = "--once", description = "Run one watchdog tick and exit")
            private boolean once;

            @Option(names = "--interval-sec", defaultValue = "30")
            private int intervalSec;

            @Option(names = "--execute", description = "Execute due worker restarts instead of previewing them")
            private boolean execute;

            @Override
            public Integer call() throws Exception {
                CliSupport.requireRange(intervalSec, 1, 3600, "interval-sec");
                do {
                    List<RuntimeWorkerRecord> due = daemonCommand.runtimeCommand.context().runtimeWorkerService().recycleDueWorkers(execute);
                    System.out.printf("Runtime watchdog tick: %d worker(s) due, execute=%s.%n", due.size(), execute);
                    if (once) {
                        break;
                    }
                    Thread.sleep(intervalSec * 1000L);
                } while (!Thread.currentThread().isInterrupted());
                return 0;
            }
        }
    }

    @Command(
            name = "oom",
            description = "Preview or execute OOM recovery",
            subcommands = {
                    OomCommand.HandleCommand.class
            }
    )
    static class OomCommand implements Runnable {
        @ParentCommand
        private RuntimeCommand runtimeCommand;

        @Spec
        private CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(System.out);
        }

        @Command(name = "handle", description = "Apply an OOM recovery strategy to workers tied to an allocation")
        static class HandleCommand implements Callable<Integer> {
            @ParentCommand
            private OomCommand oomCommand;

            @Option(names = "--allocation-id", required = true)
            private String allocationId;

            @Option(names = "--strategy", defaultValue = "restart")
            private String strategy;

            @Option(names = "--execute", description = "Actually stop, restart, or release")
            private boolean execute;

            @Override
            public Integer call() {
                CliSupport.requireOneOf(strategy, "strategy", Set.of("restart", "defrag", "stop", "release"));
                RuntimeWorkerRecord record = oomCommand.runtimeCommand.context().runtimeWorkerService()
                        .handleOom(allocationId, strategy.toLowerCase(Locale.ROOT), execute);
                System.out.printf("%s OOM strategy %s for worker %s.%n",
                        execute ? "Executed" : "Previewed", strategy, record.id());
                return 0;
            }
        }
    }

    @Command(
            name = "reconcile",
            description = "Compare containers and orchestrators with gpum allocations",
            subcommands = {
                    ReconcileCommand.DockerCommand.class,
                    ReconcileCommand.K8sCommand.class
            }
    )
    static class ReconcileCommand implements Runnable {
        @ParentCommand
        private RuntimeCommand runtimeCommand;

        @Spec
        private CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(System.out);
        }

        @Command(name = "docker", description = "Inspect visible Docker containers")
        static class DockerCommand implements Callable<Integer> {
            @ParentCommand
            private ReconcileCommand reconcileCommand;

            @Override
            public Integer call() {
                printFindings(reconcileCommand.runtimeCommand.context().containerReconcileService().reconcileDocker());
                return 0;
            }
        }

        @Command(name = "k8s", description = "Inspect Kubernetes pods and gpum allocation labels")
        static class K8sCommand implements Callable<Integer> {
            @ParentCommand
            private ReconcileCommand reconcileCommand;

            @Override
            public Integer call() {
                printFindings(reconcileCommand.runtimeCommand.context().containerReconcileService().reconcileKubernetes());
                return 0;
            }
        }

        private static void printFindings(List<ContainerReconcileService.ReconcileFinding> findings) {
            List<String[]> rows = new ArrayList<>();
            for (ContainerReconcileService.ReconcileFinding finding : findings) {
                rows.add(new String[]{
                        finding.source(),
                        finding.type(),
                        finding.detail()
                });
            }
            System.out.println(AsciiTable.getTable(
                    new String[]{"Source", "Type", "Detail"},
                    rows.toArray(String[][]::new)
            ));
        }
    }

    @Command(
            name = "migrate",
            description = "Checkpoint-based worker migration planning",
            subcommands = {
                    MigrateCommand.PlanCommand.class
            }
    )
    static class MigrateCommand implements Runnable {
        @ParentCommand
        private RuntimeCommand runtimeCommand;

        @Spec
        private CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(System.out);
        }

        @Command(name = "plan", description = "Plan or execute checkpoint/restore migration")
        static class PlanCommand implements Callable<Integer> {
            @ParentCommand
            private MigrateCommand migrateCommand;

            @Option(names = "--worker-id", required = true)
            private String workerId;

            @Option(names = "--to-node", required = true)
            private String toNode;

            @Option(names = "--execute", description = "Run checkpoint and restore commands")
            private boolean execute;

            @Override
            public Integer call() {
                RuntimeWorkerService.MigrationPlan plan = migrateCommand.runtimeCommand.context().runtimeWorkerService()
                        .migrate(workerId, toNode, execute);
                System.out.printf("Migration plan for %s -> %s%n", plan.workerId(), plan.targetNode());
                System.out.println("Executable: " + (plan.executable() ? "yes" : "no"));
                for (String step : plan.steps()) {
                    System.out.println("- " + step);
                }
                if (execute) {
                    System.out.println("Checkpoint and restore commands completed.");
                }
                return 0;
            }
        }
    }

    @Command(
            name = "zombie",
            description = "Detect or clean local GPU-bound zombie/stale processes",
            subcommands = {
                    ZombieCommand.ListCommand.class,
                    ZombieCommand.CleanCommand.class
            }
    )
    static class ZombieCommand implements Runnable {
        @ParentCommand private RuntimeCommand runtimeCommand;
        @Spec private CommandSpec spec;
        @Override public void run() { spec.commandLine().usage(System.out); }

        @Command(name = "list", description = "List processes currently bound to a known GPU")
        static class ListCommand implements Callable<Integer> {
            @ParentCommand private ZombieCommand zombieCommand;
            @Option(names = "--gpu-id", required = true) private String gpuId;

            @Override public Integer call() {
                GpuDevice gpu = findGpu(zombieCommand.runtimeCommand, gpuId);
                List<GpuProcessService.ProcessMatch> matches = zombieCommand.runtimeCommand.context()
                        .gpuProcessService().listProcessesForGpu(gpu);
                if (matches.isEmpty()) {
                    System.out.println("No GPU-bound processes found.");
                    return 0;
                }
                for (GpuProcessService.ProcessMatch match : matches) {
                    System.out.printf("pid=%d vendor=%s command=%s selectors=%s%n",
                            match.pid(), match.vendor(), match.command(), String.join(",", match.gpuSelectors()));
                }
                return 0;
            }
        }

        @Command(name = "clean", description = "Preview or terminate GPU-bound processes for one GPU")
        static class CleanCommand implements Callable<Integer> {
            @ParentCommand private ZombieCommand zombieCommand;
            @Option(names = "--gpu-id", required = true) private String gpuId;
            @Option(names = "--force") private boolean force;
            @Option(names = "--execute") private boolean execute;

            @Override public Integer call() {
                GpuDevice gpu = findGpu(zombieCommand.runtimeCommand, gpuId);
                if (!execute) {
                    System.out.println("Dry-run zombie cleanup. Matching processes:");
                    List<GpuProcessService.ProcessMatch> matches;
                    try {
                        matches = zombieCommand.runtimeCommand.context()
                                .gpuProcessService().listProcessesForGpu(gpu);
                    } catch (Exception e) {
                        System.out.println("- process discovery unavailable: " + e.getMessage());
                        return 0;
                    }
                    for (GpuProcessService.ProcessMatch match : matches) {
                        System.out.printf("- pid=%d command=%s%n", match.pid(), match.command());
                    }
                    if (matches.isEmpty()) {
                        System.out.println("- none");
                    }
                    return 0;
                }
                GpuProcessService.CleanupResult result = zombieCommand.runtimeCommand.context()
                        .gpuProcessService().cleanupGpuProcesses(gpu, force);
                System.out.printf("Killed=%d skipped=%d%n", result.killed().size(), result.skipped().size());
                return 0;
            }
        }

        private static GpuDevice findGpu(RuntimeCommand runtimeCommand, String id) {
            return runtimeCommand.context().inventoryRepository().listGpus().stream()
                    .filter(gpu -> id.equalsIgnoreCase(CliSupport.safe(gpu.deviceId()))
                            || id.equalsIgnoreCase(CliSupport.safe(gpu.uuid()))
                            || id.equalsIgnoreCase(gpu.nodeHostname() + ":" + CliSupport.safe(gpu.deviceId())))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("GPU not found: " + id));
        }
    }

    private static String formatLong(Long value) {
        return value == null ? "-" : value.toString();
    }

    private static String formatDouble(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f", value);
    }
}
