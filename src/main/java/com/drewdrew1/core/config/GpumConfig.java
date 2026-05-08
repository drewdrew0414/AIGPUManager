package com.drewdrew1.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** Holds configurable external tool paths and integration defaults for gpum. */
public class GpumConfig {
    private ToolConfig tools = new ToolConfig();
    private KubernetesConfig kubernetes = new KubernetesConfig();
    private MlflowConfig mlflow = new MlflowConfig();
    private BentoMlConfig bentoml = new BentoMlConfig();
    private MonitoringConfig monitoring = new MonitoringConfig();
    private Map<String, ExternalToolConfig> externalTools = new LinkedHashMap<>();

    public ToolConfig getTools() {
        return tools;
    }

    public void setTools(ToolConfig tools) {
        this.tools = tools == null ? new ToolConfig() : tools;
    }

    public KubernetesConfig getKubernetes() {
        return kubernetes;
    }

    public void setKubernetes(KubernetesConfig kubernetes) {
        this.kubernetes = kubernetes == null ? new KubernetesConfig() : kubernetes;
    }

    public MlflowConfig getMlflow() {
        return mlflow;
    }

    public void setMlflow(MlflowConfig mlflow) {
        this.mlflow = mlflow == null ? new MlflowConfig() : mlflow;
    }

    public BentoMlConfig getBentoml() {
        return bentoml;
    }

    public void setBentoml(BentoMlConfig bentoml) {
        this.bentoml = bentoml == null ? new BentoMlConfig() : bentoml;
    }

    public MonitoringConfig getMonitoring() {
        return monitoring;
    }

    public void setMonitoring(MonitoringConfig monitoring) {
        this.monitoring = monitoring == null ? new MonitoringConfig() : monitoring;
    }

    public Map<String, ExternalToolConfig> getExternalTools() {
        return externalTools;
    }

    public void setExternalTools(Map<String, ExternalToolConfig> externalTools) {
        this.externalTools = externalTools == null ? new LinkedHashMap<>() : new LinkedHashMap<>(externalTools);
    }
}
