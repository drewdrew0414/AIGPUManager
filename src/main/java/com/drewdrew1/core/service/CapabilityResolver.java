package com.drewdrew1.core.service;

import com.drewdrew1.core.model.HealthState;
import com.drewdrew1.core.model.InterconnectType;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Derives cross-vendor capabilities from raw detector attributes. */
public class CapabilityResolver {
    private static final Pattern NVIDIA_LINK_PATTERN = Pattern.compile("^NV\\d+|^NV$", Pattern.CASE_INSENSITIVE);

    public boolean supportsNvidiaMig(String migMode) {
        return migMode != null && !migMode.isBlank() && !"N/A".equalsIgnoreCase(migMode.trim());
    }

    public Map<String, InterconnectType> resolveNvidiaInterconnects(String topologyMatrix) {
        Map<String, InterconnectType> interconnectByIndex = new HashMap<>();
        if (topologyMatrix == null || topologyMatrix.isBlank()) {
            return interconnectByIndex;
        }

        String[] lines = topologyMatrix.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (!line.startsWith("GPU")) {
                continue;
            }
            String[] tokens = line.split("\\s+");
            if (tokens.length < 2) {
                continue;
            }

            String rowId = tokens[0].replaceAll("[^0-9]", "");
            for (int i = 1; i < tokens.length; i++) {
                if (NVIDIA_LINK_PATTERN.matcher(tokens[i]).find()) {
                    interconnectByIndex.put(rowId, InterconnectType.NVLINK);
                }
            }
        }
        return interconnectByIndex;
    }

    public InterconnectType resolveAmdInterconnect(Map<String, String> attributes) {
        if (hasPositiveCapability(attributes, List.of("xgmi", "hive", "fabric"))) {
            return InterconnectType.XGMI;
        }
        return InterconnectType.PCIE;
    }

    public InterconnectType resolveIntelInterconnect(Map<String, String> attributes) {
        if (hasPositiveCapability(attributes, List.of("xe_link", "xelink", "number_of_xe_link_ports", "max_tx_rx_speed_per_xe_link_port"))) {
            return InterconnectType.XE_LINK;
        }
        return InterconnectType.PCIE;
    }

    public HealthState resolveHealthState(Map<String, String> attributes) {
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            String value = entry.getValue().toLowerCase(Locale.ROOT);
            if (!key.contains("status")) {
                continue;
            }
            if (value.contains("critical") || value.contains("error") || value.contains("fail") || value.contains("warn")) {
                return HealthState.DEGRADED;
            }
            if (value.contains("ok") || value.contains("healthy")) {
                return HealthState.OK;
            }
        }
        return HealthState.UNKNOWN;
    }

    public Boolean resolveEccEnabled(Map<String, String> attributes) {
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (key.contains("ecc")) {
                String value = entry.getValue().toLowerCase(Locale.ROOT);
                if (value.contains("enabled") || value.contains("true") || value.contains("on")) {
                    return true;
                }
                if (value.contains("disabled") || value.contains("false") || value.contains("off")) {
                    return false;
                }
            }
        }
        return null;
    }

    private boolean hasPositiveCapability(Map<String, String> attributes, List<String> fragments) {
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            for (String fragment : fragments) {
                if (!key.contains(fragment)) {
                    continue;
                }
                String value = entry.getValue().toLowerCase(Locale.ROOT);
                if (value.isBlank() || "n/a".equals(value) || "0".equals(value) || "none".equals(value)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }
}
