package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.OpsRecord;
import com.drewdrew1.core.service.EnterpriseOpsService;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/** Exposes low-level compute policy commands beyond GPU-only allocation. */
@Command(
        name = "compute",
        mixinStandardHelpOptions = true,
        description = "CPU/RAM quota, RDMA policy, and accelerator registry",
        subcommands = {
                ComputeCommand.QuotaCommand.class,
                ComputeCommand.RdmaCommand.class,
                ComputeCommand.AcceleratorCommand.class,
                ComputeCommand.ModelQuotaCommand.class
        }
)
public class ComputeCommand implements Runnable {
    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "quota", description = "Plan or apply cgroup-style CPU/RAM/PID limits")
    static class QuotaCommand implements Callable<Integer> {
        @ParentCommand private ComputeCommand computeCommand;
        @Option(names = "--allocation-id", required = true) private String allocationId;
        @Option(names = "--name") private String name;
        @Option(names = "--cpu-cores") private Integer cpuCores;
        @Option(names = "--memory-mb") private Long memoryMb;
        @Option(names = "--pids") private Integer pids;
        @Option(names = "--pid", description = "Existing process id to classify into the cgroup") private Long pid;
        @Option(names = "--execute") private boolean execute;

        @Override public Integer call() {
            CliSupport.require(cpuCores != null || memoryMb != null || pids != null, "At least one quota limit is required.");
            if (cpuCores != null) CliSupport.requireRange(cpuCores, 1, 4096, "cpu-cores");
            if (memoryMb != null) CliSupport.require(memoryMb >= 128L && memoryMb <= 16_777_216L, "memory-mb must be between 128 and 16777216");
            if (pids != null) CliSupport.requireRange(pids, 1, 4_194_304, "pids");
            if (pid != null) CliSupport.requirePositiveLong(pid, "pid");
            EnterpriseOpsService.OpsPlan plan = computeCommand.parent.createContext().enterpriseOpsService()
                    .quotaPlan(allocationId, name, cpuCores, memoryMb, pids, pid, execute);
            printPlan(plan);
            return 0;
        }
    }

    @Command(name = "rdma", description = "Plan or record RDMA/InfiniBand bandwidth policy")
    static class RdmaCommand implements Callable<Integer> {
        @ParentCommand private ComputeCommand computeCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--node", required = true) private String node;
        @Option(names = "--device", required = true) private String device;
        @Option(names = "--bandwidth-mbit", required = true) private Integer bandwidthMbit;
        @Option(names = "--priority", defaultValue = "1") private Integer priority;
        @Option(names = "--execute") private boolean execute;

        @Override public Integer call() {
            CliSupport.requireRange(bandwidthMbit, 1, 800_000, "bandwidth-mbit");
            CliSupport.requireRange(priority, 1, 10, "priority");
            EnterpriseOpsService.OpsPlan plan = computeCommand.parent.createContext().enterpriseOpsService()
                    .rdmaPolicy(name, node, device, bandwidthMbit, priority, execute);
            printPlan(plan);
            return 0;
        }
    }

    @Command(
            name = "accelerator",
            description = "Register and list non-GPU accelerators",
            subcommands = {
                    AcceleratorCommand.RegisterCommand.class,
                    AcceleratorCommand.ListCommand.class
            }
    )
    static class AcceleratorCommand implements Runnable {
        @ParentCommand private ComputeCommand computeCommand;
        @Spec private CommandSpec spec;
        @Override public void run() { spec.commandLine().usage(System.out); }

        @Command(name = "register", description = "Register NPU/TPU/LPU or custom accelerator endpoint")
        static class RegisterCommand implements Callable<Integer> {
            @ParentCommand private AcceleratorCommand acceleratorCommand;
            @Option(names = "--name", required = true) private String name;
            @Option(names = "--kind", required = true) private String kind;
            @Option(names = "--driver", required = true) private String driver;
            @Option(names = "--endpoint", required = true) private String endpoint;
            @Option(names = "--label") private String labels;

            @Override public Integer call() {
                CliSupport.requireOneOf(kind, "kind", Set.of("npu", "tpu", "lpu", "fpga", "custom"));
                OpsRecord record = acceleratorCommand.computeCommand.parent.createContext().enterpriseOpsService()
                        .accelerator(name, kind, driver, endpoint, labels);
                System.out.printf("Registered accelerator %s (%s) id=%s%n", record.name(), kind, record.id());
                return 0;
            }
        }

        @Command(name = "list", description = "List registered accelerators")
        static class ListCommand implements Callable<Integer> {
            @ParentCommand private AcceleratorCommand acceleratorCommand;
            @Override public Integer call() {
                printRecords(acceleratorCommand.computeCommand.parent.createContext().enterpriseOpsService().list("compute", "accelerator"));
                return 0;
            }
        }
    }

    @Command(name = "model-quota", description = "Record model-specific GPU quota such as Team A V100=8")
    static class ModelQuotaCommand implements Callable<Integer> {
        @ParentCommand private ComputeCommand computeCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--tenant", required = true) private String tenant;
        @Option(names = "--gpu-model", required = true) private String gpuModel;
        @Option(names = "--max-gpus", required = true) private Integer maxGpus;

        @Override public Integer call() {
            CliSupport.requireRange(maxGpus, 1, 4096, "max-gpus");
            OpsRecord record = computeCommand.parent.createContext().enterpriseOpsService()
                    .modelQuota(name, tenant, gpuModel, maxGpus);
            System.out.printf("Recorded model quota %s tenant=%s model=%s maxGpus=%d id=%s%n",
                    record.name(), tenant, gpuModel, maxGpus, record.id());
            return 0;
        }
    }

    static void printPlan(EnterpriseOpsService.OpsPlan plan) {
        System.out.printf("Record: %s status=%s%n", plan.record().id(), plan.record().status());
        if (!plan.commands().isEmpty()) {
            System.out.println("Commands:");
            for (List<String> command : plan.commands()) {
                System.out.println("- " + String.join(" ", command));
            }
        }
        if (!plan.results().isEmpty()) {
            System.out.println("Results:");
            plan.results().forEach(result -> System.out.printf("- exit=%d command=%s%n", result.exitCode(), String.join(" ", result.command())));
        }
        for (String warning : plan.warnings()) {
            System.out.println("Warning: " + warning);
        }
    }

    static void printRecords(List<OpsRecord> records) {
        if (records.isEmpty()) {
            System.out.println("No records found.");
            return;
        }
        List<String[]> rows = new ArrayList<>();
        for (OpsRecord record : records) {
            rows.add(new String[]{
                    record.id(),
                    record.domain(),
                    record.type(),
                    record.name(),
                    record.status(),
                    CliSupport.safe(record.target()),
                    record.updatedAt().toString()
            });
        }
        System.out.println(AsciiTable.getTable(
                new String[]{"Id", "Domain", "Type", "Name", "Status", "Target", "Updated"},
                rows.toArray(String[][]::new)
        ));
    }
}
