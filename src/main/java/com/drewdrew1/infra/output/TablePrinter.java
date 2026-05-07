package com.drewdrew1.infra.output;

import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.NodeInventory;
import com.github.freva.asciitable.AsciiTable;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Renders inventory data into human-readable ASCII tables. */
public class TablePrinter {
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public void printNodes(List<NodeInventory> nodes) {
        if (nodes.isEmpty()) {
            System.out.println("No nodes found.");
            return;
        }
        String[] headers = {"Hostname", "OS", "Arch", "CPU", "MemoryMB", "LastScanned"};
        String[][] rows = new String[nodes.size()][headers.length];
        for (int i = 0; i < nodes.size(); i++) {
            NodeInventory node = nodes.get(i);
            rows[i] = new String[]{
                    node.hostname(),
                    node.osName(),
                    node.osArch(),
                    Integer.toString(node.cpuCores()),
                    Long.toString(node.memoryTotalMb()),
                    TS_FORMATTER.format(node.lastScannedAt())
            };
        }
        System.out.println(AsciiTable.getTable(headers, rows));
    }

    public void printGpus(List<GpuDevice> gpus) {
        if (gpus.isEmpty()) {
            System.out.println("No GPUs found.");
            return;
        }
        String[] headers = {
                "Node", "Vendor", "Model", "DeviceId", "PCI", "VRAM", "Free", "Util%", "TempC", "PowerW", "Link", "MIG"
        };
        String[][] rows = new String[gpus.size()][headers.length];
        for (int i = 0; i < gpus.size(); i++) {
            GpuDevice gpu = gpus.get(i);
            rows[i] = new String[]{
                    safe(gpu.nodeHostname()),
                    gpu.vendor().name(),
                    safe(gpu.model()),
                    safe(gpu.deviceId()),
                    safe(gpu.pciBusId()),
                    formatLong(gpu.vramTotalMb()),
                    formatLong(gpu.vramFreeMb()),
                    formatDouble(gpu.utilizationGpu()),
                    formatDouble(gpu.temperatureC()),
                    formatDouble(gpu.powerUsageW()),
                    gpu.interconnectType().name(),
                    gpu.supportsMig() ? "yes" : "no"
            };
        }
        System.out.println(AsciiTable.getTable(headers, rows));
    }

    private static String safe(String value) {
        return value == null ? "-" : value;
    }

    private static String formatLong(Long value) {
        return value == null ? "-" : value.toString();
    }

    private static String formatDouble(Double value) {
        return value == null ? "-" : String.format("%.1f", value);
    }
}
