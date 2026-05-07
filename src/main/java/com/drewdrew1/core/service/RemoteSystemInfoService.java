package com.drewdrew1.core.service;

import com.drewdrew1.core.model.NodeInventory;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;

import java.time.Instant;
import java.util.List;

/** Collects node-level system information by running commands over a remote executor. */
public class RemoteSystemInfoService implements NodeInfoProvider {
    private final CommandExecutor commandExecutor;

    public RemoteSystemInfoService(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    @Override
    public NodeInventory collectNodeInventory() {
        String hostname = runSingleValue("hostname");
        String osName = runSingleValue("uname", "-s");
        String osArch = runSingleValue("uname", "-m");
        int cpuCores = Integer.parseInt(runSingleValue("nproc"));
        long memoryTotalMb = Long.parseLong(runSingleValue("sh", "-lc", "awk '/MemTotal:/ {print int($2/1024)}' /proc/meminfo"));
        return new NodeInventory(hostname, osName, osArch, cpuCores, memoryTotalMb, Instant.now());
    }

    private String runSingleValue(String... command) {
        CommandResult result = commandExecutor.execute(List.of(command));
        if (!result.isSuccess()) {
            throw new IllegalStateException("Remote command failed: " + String.join(" ", command));
        }
        return result.stdout().trim();
    }
}
