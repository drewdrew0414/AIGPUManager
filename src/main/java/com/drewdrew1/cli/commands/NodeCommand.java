package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.NodeInventory;
import com.drewdrew1.core.model.ScanSummary;
import com.drewdrew1.core.service.InventoryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "node",
        description = "Node inventory commands",
        subcommands = {
                NodeCommand.ScanCommand.class,
                NodeCommand.ListCommand.class
        }
)
public class NodeCommand implements Runnable {
    @ParentCommand
    private GpuMgrCommand parent;

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    @Command(name = "scan", description = "Scan local node hardware and persist inventory")
    static class ScanCommand implements Callable<Integer> {
        @ParentCommand
        private NodeCommand nodeCommand;

        @Override
        public Integer call() {
            InventoryService inventoryService = nodeCommand.parent.createContext().inventoryService();
            ScanSummary summary = inventoryService.scanLocalNode();
            System.out.printf(
                    "Scanned node %s: %d GPU(s) discovered across %d vendor(s).%n",
                    summary.node().hostname(),
                    summary.discoveredGpuCount(),
                    summary.discoveredVendors().size()
            );
            if (!summary.warnings().isEmpty()) {
                System.out.println("Warnings:");
                for (String warning : summary.warnings()) {
                    System.out.printf("- %s%n", warning);
                }
            }
            return 0;
        }
    }

    @Command(name = "list", description = "List scanned nodes from inventory")
    static class ListCommand implements Callable<Integer> {
        @ParentCommand
        private NodeCommand nodeCommand;

        @Override
        public Integer call() {
            List<NodeInventory> nodes = nodeCommand.parent.createContext().inventoryRepository().listNodes();
            nodeCommand.parent.createContext().tablePrinter().printNodes(nodes);
            return 0;
        }
    }
}
