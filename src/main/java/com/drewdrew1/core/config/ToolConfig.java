package com.drewdrew1.core.config;

/** Holds executable names or absolute paths for vendor and integration tools. */
public class ToolConfig {
    private String nvidiaSmi = "nvidia-smi";
    private String amdSmi = "amd-smi";
    private String rocmSmi = "rocm-smi";
    private String xpuSmi = "xpu-smi";
    private String ssh = "ssh";
    private String docker = "docker";
    private String kubectl = "kubectl";
    private String mlflow = "mlflow";
    private String bentoml = "bentoml";
    private String powershell = "powershell";
    private String cmd = "cmd";
    private String bash = "bash";
    private String gpumAgentCommand = "gpum";

    public String getNvidiaSmi() {
        return nvidiaSmi;
    }

    public void setNvidiaSmi(String nvidiaSmi) {
        this.nvidiaSmi = nvidiaSmi;
    }

    public String getAmdSmi() {
        return amdSmi;
    }

    public void setAmdSmi(String amdSmi) {
        this.amdSmi = amdSmi;
    }

    public String getRocmSmi() {
        return rocmSmi;
    }

    public void setRocmSmi(String rocmSmi) {
        this.rocmSmi = rocmSmi;
    }

    public String getXpuSmi() {
        return xpuSmi;
    }

    public void setXpuSmi(String xpuSmi) {
        this.xpuSmi = xpuSmi;
    }

    public String getSsh() {
        return ssh;
    }

    public void setSsh(String ssh) {
        this.ssh = ssh;
    }

    public String getDocker() {
        return docker;
    }

    public void setDocker(String docker) {
        this.docker = docker;
    }

    public String getKubectl() {
        return kubectl;
    }

    public void setKubectl(String kubectl) {
        this.kubectl = kubectl;
    }

    public String getMlflow() {
        return mlflow;
    }

    public void setMlflow(String mlflow) {
        this.mlflow = mlflow;
    }

    public String getBentoml() {
        return bentoml;
    }

    public void setBentoml(String bentoml) {
        this.bentoml = bentoml;
    }

    public String getPowershell() {
        return powershell;
    }

    public void setPowershell(String powershell) {
        this.powershell = powershell;
    }

    public String getCmd() {
        return cmd;
    }

    public void setCmd(String cmd) {
        this.cmd = cmd;
    }

    public String getBash() {
        return bash;
    }

    public void setBash(String bash) {
        this.bash = bash;
    }

    public String getGpumAgentCommand() {
        return gpumAgentCommand;
    }

    public void setGpumAgentCommand(String gpumAgentCommand) {
        this.gpumAgentCommand = gpumAgentCommand;
    }
}
