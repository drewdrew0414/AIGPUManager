package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.Set;
import java.util.concurrent.Callable;

/** Exposes batch job, container, and interactive session workflows. */
@Command(
        name = "job",
        mixinStandardHelpOptions = true,
        description = "Batch jobs, containers, and interactive sessions",
        subcommands = {
                JobCommand.BatchCommand.class,
                JobCommand.SessionCommand.class,
                JobCommand.ListCommand.class
        }
)
public class JobCommand implements Runnable {
    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "batch", description = "Plan or submit a one-shot training command")
    static class BatchCommand implements Callable<Integer> {
        @ParentCommand private JobCommand jobCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--allocation-id") private String allocationId;
        @Option(names = "--command", required = true) private String command;
        @Option(names = "--image", description = "Optional Docker image") private String image;
        @Option(names = "--engine", defaultValue = "docker", description = "docker, apptainer, or singularity") private String engine;
        @Option(names = "--gpus", description = "Docker --gpus value", defaultValue = "all") private String gpus;
        @Option(names = "--shm-size", description = "Container shared memory size", defaultValue = "16g") private String shmSize;
        @Option(names = "--execute") private boolean execute;

        @Override public Integer call() {
            CliSupport.requireNonBlank(command, "command");
            CliSupport.requireOneOf(engine, "engine", Set.of("docker", "apptainer", "singularity"));
            ComputeCommand.printPlan(jobCommand.parent.createContext().enterpriseOpsService()
                    .batchJob(name, allocationId, command, image, engine, gpus, shmSize, execute));
            return 0;
        }
    }

    @Command(name = "session", description = "Plan or start Jupyter, VS Code tunnel, or SSH session")
    static class SessionCommand implements Callable<Integer> {
        @ParentCommand private JobCommand jobCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--allocation-id") private String allocationId;
        @Option(names = "--kind", required = true) private String kind;
        @Option(names = "--port", defaultValue = "8888") private Integer port;
        @Option(names = "--execute") private boolean execute;

        @Override public Integer call() {
            CliSupport.requireOneOf(kind, "kind", Set.of("jupyter", "vscode", "ssh"));
            CliSupport.requireRange(port, 1, 65535, "port");
            ComputeCommand.printPlan(jobCommand.parent.createContext().enterpriseOpsService()
                    .session(name, allocationId, kind, port, execute));
            return 0;
        }
    }

    @Command(name = "list", description = "List job and session records")
    static class ListCommand implements Callable<Integer> {
        @ParentCommand private JobCommand jobCommand;
        @Override public Integer call() {
            ComputeCommand.printRecords(jobCommand.parent.createContext().enterpriseOpsService().list("job", null));
            return 0;
        }
    }
}
