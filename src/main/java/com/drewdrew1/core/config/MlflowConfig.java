package com.drewdrew1.core.config;

/** Holds default MLflow integration settings. */
public class MlflowConfig {
    private boolean enabled = true;
    private String trackingUri;
    private String registryUri;
    private String experiment;
    private String profile;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTrackingUri() {
        return trackingUri;
    }

    public void setTrackingUri(String trackingUri) {
        this.trackingUri = trackingUri;
    }

    public String getRegistryUri() {
        return registryUri;
    }

    public void setRegistryUri(String registryUri) {
        this.registryUri = registryUri;
    }

    public String getExperiment() {
        return experiment;
    }

    public void setExperiment(String experiment) {
        this.experiment = experiment;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }
}
