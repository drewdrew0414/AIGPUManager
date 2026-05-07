package com.drewdrew1.core.config;

import java.util.ArrayList;
import java.util.List;

/** Describes one custom external tool that gpum can invoke. */
public class ExternalToolConfig {
    private boolean enabled = true;
    private String command;
    private List<String> defaultArgs = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getDefaultArgs() {
        return defaultArgs;
    }

    public void setDefaultArgs(List<String> defaultArgs) {
        this.defaultArgs = defaultArgs == null ? new ArrayList<>() : new ArrayList<>(defaultArgs);
    }
}
