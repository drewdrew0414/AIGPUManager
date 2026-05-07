package com.drewdrew1.core.service;

import com.drewdrew1.core.config.BentoMlConfig;
import com.drewdrew1.core.config.ExternalToolConfig;
import com.drewdrew1.core.config.GpumConfig;
import com.drewdrew1.core.config.KubernetesConfig;
import com.drewdrew1.core.config.MlflowConfig;
import com.drewdrew1.core.config.ToolConfig;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Wraps configured external integrations such as kubectl, MLflow, BentoML, and custom tools. */
public class IntegrationService {
    private final CommandExecutor commandExecutor;
    private final GpumConfig config;

    public IntegrationService(CommandExecutor commandExecutor, GpumConfig config) {
        this.commandExecutor = commandExecutor;
        this.config = config;
    }

    public CommandResult kubectl(List<String> args) {
        ToolConfig tools = config.getTools();
        List<String> command = new ArrayList<>();
        command.add(tools.getKubectl());
        KubernetesConfig kubernetes = config.getKubernetes();
        if (kubernetes.getContext() != null && !kubernetes.getContext().isBlank()) {
            command.add("--context");
            command.add(kubernetes.getContext());
        }
        command.addAll(args);
        return commandExecutor.execute(command);
    }

    public CommandResult mlflow(List<String> args) {
        ToolConfig tools = config.getTools();
        MlflowConfig mlflow = config.getMlflow();
        List<String> command = new ArrayList<>();
        command.add(tools.getMlflow());
        if (mlflow.getTrackingUri() != null && !mlflow.getTrackingUri().isBlank()) {
            command.add("--tracking-uri");
            command.add(mlflow.getTrackingUri());
        }
        if (mlflow.getRegistryUri() != null && !mlflow.getRegistryUri().isBlank()) {
            command.add("--registry-uri");
            command.add(mlflow.getRegistryUri());
        }
        if (mlflow.getProfile() != null && !mlflow.getProfile().isBlank()) {
            command.add("--profile");
            command.add(mlflow.getProfile());
        }
        command.addAll(args);
        return commandExecutor.execute(command);
    }

    public CommandResult bentoml(List<String> args) {
        ToolConfig tools = config.getTools();
        BentoMlConfig bentoml = config.getBentoml();
        List<String> command = new ArrayList<>();
        command.add(tools.getBentoml());
        command.addAll(args);
        return commandExecutor.execute(command);
    }

    public CommandResult externalTool(String name, List<String> args) {
        Map<String, ExternalToolConfig> externalTools = config.getExternalTools();
        ExternalToolConfig tool = externalTools.get(name);
        if (tool == null || !tool.isEnabled()) {
            throw new IllegalArgumentException("External tool is not configured or disabled: " + name);
        }
        if (tool.getCommand() == null || tool.getCommand().isBlank()) {
            throw new IllegalArgumentException("External tool command is blank: " + name);
        }
        List<String> command = new ArrayList<>();
        command.add(tool.getCommand());
        command.addAll(tool.getDefaultArgs());
        command.addAll(args);
        return commandExecutor.execute(command);
    }
}
