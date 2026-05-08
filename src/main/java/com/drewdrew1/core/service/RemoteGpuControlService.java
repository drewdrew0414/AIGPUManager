package com.drewdrew1.core.service;

import com.drewdrew1.core.config.GpumConfig;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.repository.InventoryRepository;
import com.drewdrew1.infra.executor.CommandExecutionException;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Routes guarded GPU write operations to a remote gpum installation over SSH. */
public class RemoteGpuControlService {
    private final InventoryRepository inventoryRepository;
    private final GpumConfig config;
    private final RemoteExecutorFactory remoteExecutorFactory;

    public RemoteGpuControlService(
            InventoryRepository inventoryRepository,
            GpumConfig config,
            RemoteExecutorFactory remoteExecutorFactory
    ) {
        this.inventoryRepository = inventoryRepository;
        this.config = config;
        this.remoteExecutorFactory = remoteExecutorFactory;
    }

    public RemoteExecutionResult applySettings(GpuDevice gpu, GpuControlService.SetRequest request, String sshUserOverride) {
        List<String> command = new ArrayList<>();
        command.add("env");
        command.add("GPUM_ENABLE_HARDWARE_WRITE=1");
        command.add(config.getTools().getGpumAgentCommand());
        command.add("gpu");
        command.add("set");
        command.add("--id");
        command.add(remoteSelector(gpu));
        if (request.powerLimit() != null) {
            command.add("--power-limit");
            command.add(Integer.toString(request.powerLimit()));
        }
        if (request.clockFix() != null) {
            command.add("--clock-fix");
            command.add(request.clockFix());
        }
        if (request.eccMode() != null) {
            command.add("--ecc");
            command.add(request.eccMode());
        }
        if (request.computeMode() != null) {
            command.add("--compute-mode");
            command.add(request.computeMode());
        }
        if (request.allowAllocated()) {
            command.add("--allow-allocated");
        }
        if (request.allowRebootRequired()) {
            command.add("--allow-reboot-required");
        }
        command.add("--apply");
        return executeRemote(gpu, sshUserOverride, command);
    }

    public RemoteExecutionResult resetGpu(GpuDevice gpu, GpuControlService.ResetRequest request, String sshUserOverride, boolean drainFirst) {
        List<String> command = new ArrayList<>();
        command.add("env");
        command.add("GPUM_ENABLE_HARDWARE_WRITE=1");
        command.add(config.getTools().getGpumAgentCommand());
        command.add("gpu");
        command.add("reset");
        command.add("--id");
        command.add(remoteSelector(gpu));
        command.add(request.hardReset() ? "--hard" : "--soft");
        if (request.allowLinkedReset()) {
            command.add("--allow-linked-reset");
        }
        if (drainFirst) {
            command.add("--drain-first");
        }
        command.add("--apply");
        return executeRemote(gpu, sshUserOverride, command);
    }

    public List<String> previewCommand(GpuDevice gpu, List<String> remoteCommand, String sshUserOverride) {
        RemoteTarget target = resolveTarget(gpu, sshUserOverride);
        List<String> ssh = new ArrayList<>();
        ssh.add(config.getTools().getSsh());
        ssh.add(target.user() + "@" + target.address());
        ssh.add(String.join(" ", remoteCommand));
        return ssh;
    }

    private RemoteExecutionResult executeRemote(GpuDevice gpu, String sshUserOverride, List<String> remoteCommand) {
        RemoteTarget target = resolveTarget(gpu, sshUserOverride);
        CommandExecutor executor = remoteExecutorFactory.create(target.address(), target.user());
        try {
            CommandResult result = executor.execute(remoteCommand);
            if (!result.isSuccess()) {
                throw new IllegalStateException("Remote hardware write failed: " + safeError(result));
            }
            return new RemoteExecutionResult(target.address(), target.user(), remoteCommand, result);
        } catch (CommandExecutionException e) {
            throw new IllegalStateException("Remote hardware write failed: " + e.getMessage(), e);
        }
    }

    private RemoteTarget resolveTarget(GpuDevice gpu, String sshUserOverride) {
        inventoryRepository.initialize();
        Map<String, String> attrs = inventoryRepository.getNodeAttributes(gpu.nodeHostname());
        String address = attrs.get("remote.address");
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("Remote address is not recorded for node " + gpu.nodeHostname() + ". Scan it via SSH first.");
        }
        String user = sshUserOverride == null || sshUserOverride.isBlank()
                ? attrs.get("remote.user")
                : sshUserOverride.trim();
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("Remote SSH user is not recorded for node " + gpu.nodeHostname() + ".");
        }
        return new RemoteTarget(address.trim(), user.trim());
    }

    private String remoteSelector(GpuDevice gpu) {
        if (gpu.uuid() != null && !gpu.uuid().isBlank()) {
            return gpu.uuid();
        }
        if (gpu.deviceId() != null && !gpu.deviceId().isBlank()) {
            return gpu.deviceId();
        }
        throw new IllegalArgumentException("GPU selector is unavailable for the remote device.");
    }

    private String safeError(CommandResult result) {
        if (result.stderr() != null && !result.stderr().isBlank()) {
            return result.stderr().trim();
        }
        if (result.stdout() != null && !result.stdout().isBlank()) {
            return result.stdout().trim();
        }
        return "exit=" + result.exitCode();
    }

    /** Creates a remote executor for one SSH target. */
    public interface RemoteExecutorFactory {
        CommandExecutor create(String address, String sshUser);
    }

    /** Resolves one remote target used for agentless remote writes. */
    public record RemoteTarget(String address, String user) {
    }

    /** Returns the remote execution metadata and captured output. */
    public record RemoteExecutionResult(String address, String sshUser, List<String> command, CommandResult result) {
    }
}
