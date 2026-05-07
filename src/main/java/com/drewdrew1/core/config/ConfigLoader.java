package com.drewdrew1.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Loads gpum YAML configuration files and falls back to sensible defaults. */
public final class ConfigLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<Path> DEFAULT_LOCATIONS = List.of(
            Path.of("gpum.yaml"),
            Path.of("config", "gpum.yaml")
    );

    private ConfigLoader() {
    }

    public static GpumConfig load(Path explicitPath) {
        Path path = explicitPath;
        if (path == null) {
            path = DEFAULT_LOCATIONS.stream().filter(Files::exists).findFirst().orElse(null);
        }
        if (path == null || !Files.exists(path)) {
            return new GpumConfig();
        }
        try {
            GpumConfig config = YAML.readValue(path.toFile(), GpumConfig.class);
            return config == null ? new GpumConfig() : config;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to load config: " + path, e);
        }
    }

    public static String dumpDefaults() {
        try {
            return YAML.writeValueAsString(new GpumConfig());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render default config", e);
        }
    }
}
