package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.GpuDevice;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "gpu",
        description = "GPU inventory commands",
        subcommands = {
                GpuCommand.ListCommand.class
        }
)
public class GpuCommand implements Runnable {
    @ParentCommand
    private GpuMgrCommand parent;

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    @Command(name = "list", description = "List GPUs from inventory")
    static class ListCommand implements Callable<Integer> {
        @ParentCommand
        private GpuCommand gpuCommand;

        @Override
        public Integer call() {
            List<GpuDevice> gpus = gpuCommand.parent.createContext().inventoryRepository().listGpus();
            gpuCommand.parent.createContext().tablePrinter().printGpus(gpus);
            return 0;
        }
    }
}
