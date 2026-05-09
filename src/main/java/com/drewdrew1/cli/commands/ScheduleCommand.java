package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.OpsRecord;
import com.drewdrew1.core.service.EnterpriseOpsService;
import com.drewdrew1.core.service.SchedulingEngineService;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Exposes production scheduling controls such as queues, reservations, fair-share, and gang plans. */
@Command(
        name = "schedule",
        mixinStandardHelpOptions = true,
        description = "Multi-level queues, reservations, fair-share, and gang scheduling",
        subcommands = {
                ScheduleCommand.QueueCommand.class,
                ScheduleCommand.ReserveCommand.class,
                ScheduleCommand.FairShareCommand.class,
                ScheduleCommand.GangCommand.class,
                ScheduleCommand.PreemptCommand.class,
                ScheduleCommand.PlaceCommand.class,
                ScheduleCommand.BackfillCommand.class
        }
)
public class ScheduleCommand implements Runnable {
    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "queue", description = "Create or list logical scheduling queues", subcommands = {
            QueueCommand.CreateCommand.class,
            QueueCommand.ListCommand.class
    })
    static class QueueCommand implements Runnable {
        @ParentCommand private ScheduleCommand scheduleCommand;
        @Spec private CommandSpec spec;
        @Override public void run() { spec.commandLine().usage(System.out); }

        @Command(name = "create", description = "Create a tenant/project scheduling queue")
        static class CreateCommand implements Callable<Integer> {
            @ParentCommand private QueueCommand queueCommand;
            @Option(names = "--name", required = true) private String name;
            @Option(names = "--tenant", required = true) private String tenant;
            @Option(names = "--weight", defaultValue = "1") private Integer weight;
            @Option(names = "--max-gpus", defaultValue = "1") private Integer maxGpus;
            @Option(names = "--preemptible") private boolean preemptible;
            @Override public Integer call() {
                CliSupport.requireRange(weight, 1, 100, "weight");
                CliSupport.requirePositive(maxGpus, "max-gpus");
                OpsRecord record = queueCommand.scheduleCommand.parent.createContext().enterpriseOpsService()
                        .queue(name, tenant, weight, maxGpus, preemptible);
                System.out.printf("Created schedule queue %s id=%s%n", record.name(), record.id());
                return 0;
            }
        }

        @Command(name = "list", description = "List logical scheduling queues")
        static class ListCommand implements Callable<Integer> {
            @ParentCommand private QueueCommand queueCommand;
            @Override public Integer call() {
                ComputeCommand.printRecords(queueCommand.scheduleCommand.parent.createContext().enterpriseOpsService().list("schedule", "queue"));
                return 0;
            }
        }
    }

    @Command(name = "reserve", description = "Create, list, or cancel advance reservations", subcommands = {
            ReserveCommand.CreateCommand.class,
            ReserveCommand.ListCommand.class,
            ReserveCommand.CancelCommand.class
    })
    static class ReserveCommand implements Runnable {
        @ParentCommand private ScheduleCommand scheduleCommand;
        @Spec private CommandSpec spec;
        @Override public void run() { spec.commandLine().usage(System.out); }

        @Command(name = "create", description = "Reserve future capacity")
        static class CreateCommand implements Callable<Integer> {
            @ParentCommand private ReserveCommand reserveCommand;
            @Option(names = "--name", required = true) private String name;
            @Option(names = "--queue", required = true) private String queue;
            @Option(names = "--start", required = true, description = "ISO-8601 UTC timestamp") private String start;
            @Option(names = "--end", required = true, description = "ISO-8601 UTC timestamp") private String end;
            @Option(names = "--gpus", required = true) private Integer gpus;
            @Option(names = "--nodes", defaultValue = "1") private Integer nodes;
            @Option(names = "--project") private String project;
            @Override public Integer call() {
                CliSupport.requirePositive(gpus, "gpus");
                CliSupport.requirePositive(nodes, "nodes");
                OpsRecord record = reserveCommand.scheduleCommand.parent.createContext().enterpriseOpsService()
                        .reservation(name, queue, CliSupport.parseInstant(start, "start"), CliSupport.parseInstant(end, "end"), gpus, nodes, project);
                System.out.printf("Created reservation %s id=%s%n", record.name(), record.id());
                return 0;
            }
        }

        @Command(name = "list", description = "List reservations")
        static class ListCommand implements Callable<Integer> {
            @ParentCommand private ReserveCommand reserveCommand;
            @Override public Integer call() {
                ComputeCommand.printRecords(reserveCommand.scheduleCommand.parent.createContext().enterpriseOpsService().list("schedule", "reservation"));
                return 0;
            }
        }

        @Command(name = "cancel", description = "Cancel reservation by id")
        static class CancelCommand implements Callable<Integer> {
            @ParentCommand private ReserveCommand reserveCommand;
            @Option(names = "--id", required = true) private String id;
            @Override public Integer call() {
                int changed = reserveCommand.scheduleCommand.parent.createContext().enterpriseOpsService().cancel(id);
                System.out.printf("Cancelled %d reservation(s).%n", changed);
                return 0;
            }
        }
    }

    @Command(name = "fair-share", description = "Calculate fair-share score from historical allocation usage")
    static class FairShareCommand implements Callable<Integer> {
        @ParentCommand private ScheduleCommand scheduleCommand;
        @Option(names = "--owner") private String owner;
        @Option(names = "--window-hours", defaultValue = "168") private Integer windowHours;
        @Override public Integer call() {
            CliSupport.requirePositive(windowHours, "window-hours");
            EnterpriseOpsService.FairShareReport report = scheduleCommand.parent.createContext().enterpriseOpsService().fairShare(owner, windowHours);
            System.out.println(AsciiTable.getTable(new String[]{"Owner", "WindowHours", "Allocations", "GpuHours", "Score"}, new String[][]{{
                    report.owner(),
                    Integer.toString(report.windowHours()),
                    Integer.toString(report.allocations()),
                    String.format(java.util.Locale.ROOT, "%.2f", report.gpuHours()),
                    String.format(java.util.Locale.ROOT, "%.1f", report.score())
            }}));
            return 0;
        }
    }

    @Command(name = "gang", description = "Plan gang scheduling for distributed jobs")
    static class GangCommand implements Callable<Integer> {
        @ParentCommand private ScheduleCommand scheduleCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--nodes", required = true) private Integer nodes;
        @Option(names = "--gpus-per-node", required = true) private Integer gpusPerNode;
        @Option(names = "--label-selector") private String labelSelector;
        @Option(names = "--reserve") private boolean reserve;
        @Override public Integer call() {
            CliSupport.requirePositive(nodes, "nodes");
            CliSupport.requirePositive(gpusPerNode, "gpus-per-node");
            ComputeCommand.printPlan(scheduleCommand.parent.createContext().enterpriseOpsService().gangPlan(name, nodes, gpusPerNode, labelSelector, reserve));
            return 0;
        }
    }

    @Command(name = "preempt", description = "Plan or execute low-priority allocation suspension for higher-priority work")
    static class PreemptCommand implements Callable<Integer> {
        @ParentCommand private ScheduleCommand scheduleCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--victim-allocation-id", required = true) private String victimAllocationId;
        @Option(names = "--incoming") private String incoming;
        @Option(names = "--suspend-command") private String suspendCommand;
        @Option(names = "--resume-command") private String resumeCommand;
        @Option(names = "--execute") private boolean execute;

        @Override public Integer call() {
            ComputeCommand.printPlan(scheduleCommand.parent.createContext().enterpriseOpsService()
                    .preempt(name, victimAllocationId, incoming, suspendCommand, resumeCommand, execute));
            return 0;
        }
    }

    @Command(name = "place", description = "Run bin-packing placement simulation with best-fit, worst-fit, or topology strategy")
    static class PlaceCommand implements Callable<Integer> {
        @ParentCommand private ScheduleCommand scheduleCommand;
        @Option(names = "--gpus", required = true) private Integer gpus;
        @Option(names = "--vram") private Long vram;
        @Option(names = "--model") private String model;
        @Option(names = "--strategy", defaultValue = "best-fit") private String strategy;

        @Override public Integer call() {
            CliSupport.requirePositive(gpus, "gpus");
            CliSupport.requireOneOf(strategy, "strategy", java.util.Set.of("best-fit", "worst-fit", "topology"));
            SchedulingEngineService.PlacementPlan plan = scheduleCommand.parent.createContext()
                    .schedulingEngineService().place(gpus, vram, model, strategy);
            System.out.printf("Placement feasible=%s selectedNode=%s reason=%s%n",
                    plan.feasible(), CliSupport.safe(plan.selectedNode()), plan.reason());
            List<String[]> rows = new ArrayList<>();
            for (SchedulingEngineService.NodeScore score : plan.candidates()) {
                rows.add(new String[]{
                        score.node(),
                        Integer.toString(score.totalGpus()),
                        Integer.toString(score.usedGpus()),
                        Integer.toString(score.freeGpus()),
                        Integer.toString(score.topologyScore()),
                        String.format(java.util.Locale.ROOT, "%.2f", score.fragmentationRatio())
                });
            }
            if (!rows.isEmpty()) {
                System.out.println(AsciiTable.getTable(
                        new String[]{"Node", "Total", "Used", "Free", "Topo", "Frag"},
                        rows.toArray(String[][]::new)
                ));
            }
            return 0;
        }
    }

    @Command(name = "backfill", description = "Find short idle windows for backfilling small jobs")
    static class BackfillCommand implements Callable<Integer> {
        @ParentCommand private ScheduleCommand scheduleCommand;
        @Option(names = "--queue", defaultValue = "default") private String queue;
        @Option(names = "--max-minutes", defaultValue = "60") private Integer maxMinutes;
        @Option(names = "--max-gpus", defaultValue = "1") private Integer maxGpus;

        @Override public Integer call() {
            CliSupport.requirePositive(maxMinutes, "max-minutes");
            CliSupport.requirePositive(maxGpus, "max-gpus");
            SchedulingEngineService.BackfillPlan plan = scheduleCommand.parent.createContext()
                    .schedulingEngineService().backfill(maxMinutes, maxGpus, queue);
            System.out.printf("Backfill feasible=%s queue=%s freeGpus=%d idleMinutes=%d reason=%s%n",
                    plan.feasible(), plan.queue(), plan.freeGpus(), plan.idleMinutes(), plan.reason());
            return 0;
        }
    }
}
