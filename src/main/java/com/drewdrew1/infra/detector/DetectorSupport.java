package com.drewdrew1.infra.detector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DetectorSupport {
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DetectorSupport() {
    }

    static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString().trim());
        return values;
    }

    static Long parseLongValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("[^0-9.-]", "");
        if (normalized.isBlank() || "-".equals(normalized)) {
            return null;
        }
        return Math.round(Double.parseDouble(normalized));
    }

    static Double parseDoubleValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("[^0-9.-]", "");
        if (normalized.isBlank() || "-".equals(normalized)) {
            return null;
        }
        return Double.parseDouble(normalized);
    }

    static String firstValue(Map<String, String> attributes, String... fragments) {
        for (String fragment : fragments) {
            String normalizedFragment = normalizeKey(fragment);
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                if (normalizeKey(entry.getKey()).contains(normalizedFragment)) {
                    return blankToNull(entry.getValue());
                }
            }
        }
        return null;
    }

    static boolean hasAnyKey(Map<String, String> attributes, String... fragments) {
        for (String fragment : fragments) {
            String normalizedFragment = normalizeKey(fragment);
            for (String key : attributes.keySet()) {
                if (normalizeKey(key).contains(normalizedFragment)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String normalizeKey(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    static Map<String, Map<String, String>> extractGpuMaps(String json) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            LinkedHashMap<String, Map<String, String>> devices = new LinkedHashMap<>();
            collectGpuMaps(root, devices);
            return devices;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse detector JSON payload", e);
        }
    }

    static void mergeDeviceMaps(Map<String, Map<String, String>> target, Map<String, Map<String, String>> source) {
        for (Map.Entry<String, Map<String, String>> entry : source.entrySet()) {
            Map<String, String> incoming = entry.getValue();
            String compatibleKey = findCompatibleKey(target, incoming);
            if (compatibleKey != null) {
                target.get(compatibleKey).putAll(incoming);
            } else {
                target.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashMap<>()).putAll(incoming);
            }
        }
    }

    private static void collectGpuMaps(JsonNode node, Map<String, Map<String, String>> devices) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectGpuMaps(child, devices);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }

        if (isCandidateGpuObject(node)) {
            Map<String, String> flattened = new LinkedHashMap<>();
            flatten("", node, flattened);
            String key = deriveDeviceKey(flattened);
            if (key != null) {
                devices.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).putAll(flattened);
            }
        }

        node.fields().forEachRemaining(entry -> collectGpuMaps(entry.getValue(), devices));
    }

    private static boolean isCandidateGpuObject(JsonNode node) {
        Map<String, String> flattened = new LinkedHashMap<>();
        flatten("", node, flattened);
        if (flattened.size() < 2 || flattened.size() > 120) {
            return false;
        }
        String key = deriveDeviceKey(flattened);
        if (key == null) {
            return false;
        }
        return hasAnyKey(flattened,
                "device_name",
                "product_name",
                "card_series",
                "memory",
                "temperature",
                "power",
                "xgmi",
                "xe_link",
                "utilization",
                "gpu_use",
                "gpu_utilization");
    }

    private static String deriveDeviceKey(Map<String, String> flattened) {
        String deviceIndex = firstValue(flattened, "device_id", "gpu_id", "gpu");
        if (deviceIndex != null) {
            return "id:" + deviceIndex;
        }
        String uuid = firstValue(flattened, "uuid");
        if (uuid != null) {
            return "uuid:" + uuid;
        }
        String pci = firstValue(flattened, "pci_bdf_address", "pci_bus_id", "pci_bus", "bdf", "pci");
        if (pci != null) {
            return "pci:" + pci;
        }
        String deviceId = firstValue(flattened, "id");
        if (deviceId != null) {
            return "id:" + deviceId;
        }
        return null;
    }

    private static String findCompatibleKey(Map<String, Map<String, String>> target, Map<String, String> incoming) {
        String incomingUuid = firstValue(incoming, "uuid", "unique_id");
        String incomingPci = firstValue(incoming, "pci_bdf_address", "pci_bdf", "pci_bus", "bdf");
        String incomingId = firstValue(incoming, "device_id", "gpu_id", "gpu");

        for (Map.Entry<String, Map<String, String>> entry : target.entrySet()) {
            Map<String, String> current = entry.getValue();
            String currentUuid = firstValue(current, "uuid", "unique_id");
            String currentPci = firstValue(current, "pci_bdf_address", "pci_bdf", "pci_bus", "bdf");
            String currentId = firstValue(current, "device_id", "gpu_id", "gpu");

            if (matches(currentUuid, incomingUuid) || matches(currentPci, incomingPci) || matches(currentId, incomingId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static boolean matches(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> output) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isValueNode()) {
            output.put(prefix, node.asText());
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String childPrefix = prefix.isBlank() ? Integer.toString(i) : prefix + "_" + i;
                flatten(childPrefix, node.get(i), output);
            }
            return;
        }

        node.fields().forEachRemaining(entry -> {
            String normalized = normalizeKey(entry.getKey());
            String childPrefix = prefix.isBlank() ? normalized : prefix + "_" + normalized;
            flatten(childPrefix, entry.getValue(), output);
        });
    }
}
