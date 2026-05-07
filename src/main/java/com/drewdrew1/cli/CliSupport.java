package com.drewdrew1.cli;

import com.drewdrew1.core.model.GpuDevice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Holds common validation and formatting helpers for CLI commands. */
public final class CliSupport {
    private static final Set<String> KNOWN_CAPABILITIES = Set.of(
            "nvlink", "mig", "p2p", "rocm", "cuda", "xgmi", "xe-link", "xe_link", "oneapi"
    );

    private CliSupport() {
    }

    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void requireNonBlank(String value, String field) {
        require(value != null && !value.isBlank(), field + " must not be blank");
    }

    public static void requirePositive(Integer value, String field) {
        require(value != null && value > 0, field + " must be > 0");
    }

    public static void requirePositiveLong(Long value, String field) {
        require(value != null && value > 0L, field + " must be > 0");
    }

    public static void requireRange(Integer value, int min, int max, String field) {
        require(value != null && value >= min && value <= max, field + " must be between " + min + " and " + max);
    }

    public static void requireOneOf(String value, String field, Set<String> allowed) {
        require(value != null && allowed.contains(value.toLowerCase(Locale.ROOT)),
                field + " must be one of: " + String.join(", ", allowed));
    }

    public static void ensureParentDirectory(Path path) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to prepare path: " + path);
        }
    }

    public static Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be ISO-8601 UTC timestamp");
        }
    }

    public static Map<String, String> parseLabels(List<String> pairs) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String pair : pairs) {
            String[] parts = pair.split("=", 2);
            require(parts.length == 2, "Label must be in key=value form: " + pair);
            requireNonBlank(parts[0], "label key");
            requireNonBlank(parts[1], "label value");
            labels.put(parts[0].trim(), parts[1].trim());
        }
        return labels;
    }

    public static void validateCapabilityFilter(String capability) {
        requireOneOf(capability, "capability", KNOWN_CAPABILITIES);
    }

    public static boolean matchesCapability(GpuDevice gpu, String capability) {
        if (capability == null) {
            return true;
        }
        return switch (capability.toLowerCase(Locale.ROOT)) {
            case "mig" -> gpu.supportsMig();
            case "nvlink" -> "NVLINK".equals(gpu.interconnectType().name());
            case "p2p" -> !"UNKNOWN".equals(gpu.interconnectType().name()) && !"PCIE".equals(gpu.interconnectType().name());
            case "rocm" -> "AMD".equals(gpu.vendor().name());
            case "cuda" -> "NVIDIA".equals(gpu.vendor().name());
            case "xgmi" -> "XGMI".equals(gpu.interconnectType().name());
            case "xe-link", "xe_link", "oneapi" -> "INTEL".equals(gpu.vendor().name());
            default -> false;
        };
    }

    public static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    public static String joinWarnings(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return "none";
        }
        return String.join("; ", warnings);
    }

    public static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    public static List<String> copyStrings(List<String> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    public static <T> T requireNonNull(T value, String field) {
        return Objects.requireNonNull(value, field + " must not be null");
    }
}
