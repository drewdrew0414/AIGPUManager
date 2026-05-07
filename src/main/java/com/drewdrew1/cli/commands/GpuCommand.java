package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.AppContext;
import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.GpuDevice;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/** Exposes GPU inventory, health, and low-level device operations. */
@Command(
        name = "gpu",
        mixinStandardHelpOptions = true,
        description = "GPU device operations",
        subcommands = {
                GpuCommand.ListCommand.class,
                GpuCommand.StatsCommand.class,
                GpuCommand.HealthCommand.class,
                GpuCommand.SetCommand.class,
                GpuCommand.ResetCommand.class,
                GpuCommand.TopologyCommand.class
        }
)
public class GpuCommand implements Runnable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ParentCommand
    private GpuMgrCommand parent;

    @Spec
    private CommandSpec spec;

    AppContext context() {
        return parent.createContext();
    }

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    @Command(name = "list", description = "List GPUs from inventory")
    static class ListCommand implements Callable<Integer> {
        @ParentCommand
        private GpuCommand gpuCommand;

        @Option(names = "--available")
        private boolean available;

        @Option(names = "--min-vram")
        private Long minVramMb;

        @Option(names = "--capability")
        private String capability;

        @Option(names = "--pci-gen")
        private Integer pciGen;

        @Override
        public Integer call() {
            if (capability != null) {
                CliSupport.validateCapabilityFilter(capability);
            }
            if (pciGen != null) {
                CliSupport.require(Set.of(3, 4, 5).contains(pciGen), "pci-gen must be 3, 4 or 5");
            }
            List<GpuDevice> gpus = new ArrayList<>(gpuCommand.context().inventoryRepository().listGpus());
            Map<String, Map<String, String>> nodeAttributes = gpuCommand.context().inventoryRepository().listNodeAttributes();
            gpus.removeIf(gpu -> available && (gpu.vramFreeMb() == null || gpu.vramFreeMb() <= 0));
            gpus.removeIf(gpu -> minVramMb != null && (gpu.vramTotalMb() == null || gpu.vramTotalMb() < minVramMb));
            gpus.removeIf(gpu -> capability != null && !CliSupport.matchesCapability(gpu, capability));
            gpus.removeIf(gpu -> pciGen != null && !matchesPciGen(gpu, pciGen, nodeAttributes));
            gpuCommand.context().tablePrinter().printGpus(gpus);
            return 0;
        }

        private boolean matchesPciGen(GpuDevice gpu, int pciGen, Map<String, Map<String, String>> nodeAttributes) {
            Map<String, String> attrs = nodeAttributes.getOrDefault(gpu.nodeHostname(), Map.of());
            String value = attrs.get("gpu.pci_gen." + CliSupport.safe(gpu.deviceId()));
            if (value == null || value.isBlank()) {
                return true;
            }
            return Integer.toString(pciGen).equals(value.trim());
        }
    }

    @Command(name = "stats", description = "Show GPU metrics")
    static class StatsCommand implements Callable<Integer> {
        @ParentCommand
        private GpuCommand gpuCommand;

        @Option(names = "--watch")
        private boolean watch;

        @Option(names = "--json")
        private boolean json;

        @Option(names = "--history")
        private Integer historyHours;

        @Option(names = "--export")
        private String exportTarget;

        @Override
        public Integer call() throws Exception {
            if (historyHours != null) {
                CliSupport.requirePositive(historyHours, "history");
            }
            if (exportTarget != null) {
                CliSupport.requireOneOf(exportTarget, "export", Set.of("csv", "influxdb"));
            }
            do {
                List<GpuDevice> gpus = new ArrayList<>(gpuCommand.context().inventoryRepository().listGpus());
                if (historyHours != null) {
                    long cutoffMillis = System.currentTimeMillis() - (historyHours * 3600_000L);
                    gpus.removeIf(gpu -> gpu.lastScannedAt() == null || gpu.lastScannedAt().toEpochMilli() < cutoffMillis);
                }
                if (json) {
                    List<Map<String, Object>> payload = new ArrayList<>();
                    for (GpuDevice gpu : gpus) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("node", gpu.nodeHostname());
                        row.put("vendor", gpu.vendor().name());
                        row.put("deviceId", gpu.deviceId());
                        row.put("model", gpu.model());
                        row.put("utilizationGpu", gpu.utilizationGpu());
                        row.put("utilizationMemory", gpu.utilizationMemory());
                        row.put("temperatureC", gpu.temperatureC());
                        row.put("powerUsageW", gpu.powerUsageW());
                        row.put("vramFreeMb", gpu.vramFreeMb());
                        payload.add(row);
                    }
                    System.out.println(OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
                } else {
                    List<String[]> rows = new ArrayList<>();
                    for (GpuDevice gpu : gpus) {
                        rows.add(new String[]{
                                CliSupport.safe(gpu.nodeHostname()),
                                gpu.vendor().name(),
                                CliSupport.safe(gpu.model()),
                                format(gpu.utilizationGpu()),
                                format(gpu.utilizationMemory()),
                                format(gpu.temperatureC()),
                                format(gpu.powerUsageW()),
                                gpu.vramFreeMb() == null ? "-" : gpu.vramFreeMb().toString()
                        });
                    }
                    System.out.println(AsciiTable.getTable(
                            new String[]{"Node", "Vendor", "Model", "GPU%", "MEM%", "TempC", "PowerW", "FreeMB"},
                            rows.toArray(String[][]::new)
                    ));
                }
                if ("csv".equalsIgnoreCase(exportTarget)) {
                    exportCsv(gpuCommand.context().dbPath().resolveSibling("gpu-stats-export.csv"), gpus);
                    System.out.println("Exported current snapshot to gpu-stats-export.csv");
                } else if ("influxdb".equalsIgnoreCase(exportTarget)) {
                    exportInflux(gpuCommand.context().dbPath().resolveSibling("gpu-stats-export.lp"), gpus);
                    System.out.println("Exported current snapshot to gpu-stats-export.lp");
                }
                if (!watch) {
                    break;
                }
                Thread.sleep(1000L);
            } while (!Thread.currentThread().isInterrupted());
            return 0;
        }

        private void exportCsv(Path path, List<GpuDevice> gpus) throws Exception {
            CliSupport.ensureParentDirectory(path);
            List<String> lines = new ArrayList<>();
            lines.add("node,vendor,device_id,model,gpu_util,mem_util,temp_c,power_w,vram_free_mb");
            for (GpuDevice gpu : gpus) {
                lines.add(String.join(",",
                        csv(gpu.nodeHostname()),
                        gpu.vendor().name(),
                        csv(gpu.deviceId()),
                        csv(gpu.model()),
                        format(gpu.utilizationGpu()),
                        format(gpu.utilizationMemory()),
                        format(gpu.temperatureC()),
                        format(gpu.powerUsageW()),
                        gpu.vramFreeMb() == null ? "" : gpu.vramFreeMb().toString()));
            }
            Files.write(path, lines);
        }

        private void exportInflux(Path path, List<GpuDevice> gpus) throws Exception {
            CliSupport.ensureParentDirectory(path);
            List<String> lines = new ArrayList<>();
            for (GpuDevice gpu : gpus) {
                String tags = "node=" + influxTag(gpu.nodeHostname())
                        + ",vendor=" + influxTag(gpu.vendor().name())
                        + ",device_id=" + influxTag(gpu.deviceId())
                        + ",model=" + influxTag(gpu.model());
                String fields = "gpu_util=" + influxNumber(gpu.utilizationGpu())
                        + ",mem_util=" + influxNumber(gpu.utilizationMemory())
                        + ",temp_c=" + influxNumber(gpu.temperatureC())
                        + ",power_w=" + influxNumber(gpu.powerUsageW())
                        + ",vram_free_mb=" + influxInteger(gpu.vramFreeMb());
                long tsNanos = (gpu.lastScannedAt() == null ? System.currentTimeMillis() : gpu.lastScannedAt().toEpochMilli()) * 1_000_000L;
                lines.add("gpum_gpu_stats," + tags + " " + fields + " " + tsNanos);
            }
            Files.write(path, lines);
        }

        private String csv(String value) {
            return value == null ? "" : '"' + value.replace("\"", "\"\"") + '"';
        }

        private String influxTag(String value) {
            String safe = value == null ? "unknown" : value;
            return safe.replace("\\", "\\\\").replace(" ", "\\ ").replace(",", "\\,").replace("=", "\\=");
        }

        private String influxNumber(Double value) {
            return value == null ? "0.0" : String.format(Locale.ROOT, "%.3f", value);
        }

        private String influxInteger(Long value) {
            return value == null ? "0i" : value + "i";
        }
    }

    @Command(name = "health", description = "Assess GPU health")
    static class HealthCommand implements Callable<Integer> {
        @ParentCommand
        private GpuCommand gpuCommand;

        @Option(names = "--check-ecc")
        private boolean checkEcc;

        @Option(names = "--thermal-test")
        private boolean thermalTest;

        @Option(names = "--memory-test")
        private boolean memoryTest;

        @Option(names = "--report")
        private boolean report;

        @Override
        public Integer call() {
            List<GpuDevice> gpus = gpuCommand.context().inventoryRepository().listGpus();
            List<String[]> rows = new ArrayList<>();
            for (GpuDevice gpu : gpus) {
                String ecc = checkEcc ? String.valueOf(gpu.eccEnabled()) : "skipped";
                String thermal = thermalTest ? assessThermal(gpu) : "skipped";
                String memory = memoryTest ? assessMemory(gpu) : "skipped";
                String overall = overall(ecc, thermal, memory);
                rows.add(new String[]{
                        CliSupport.safe(gpu.nodeHostname()),
                        CliSupport.safe(gpu.deviceId()),
                        CliSupport.safe(gpu.model()),
                        ecc,
                        thermal,
                        memory,
                        overall
                });
            }
            System.out.println(AsciiTable.getTable(
                    new String[]{"Node", "Id", "Model", "ECC", "Thermal", "Memory", "Overall"},
                    rows.toArray(String[][]::new)
            ));
            if (report) {
                System.out.println("Health report generated from latest inventory snapshot.");
            }
            return 0;
        }

        private String assessThermal(GpuDevice gpu) {
            if (gpu.temperatureC() == null) {
                return "unknown";
            }
            if (gpu.temperatureC() >= 85.0) {
                return "critical";
            }
            if (gpu.temperatureC() >= 75.0) {
                return "warn";
            }
            return "ok";
        }

        private String assessMemory(GpuDevice gpu) {
            if (gpu.vramTotalMb() == null || gpu.vramFreeMb() == null) {
                return "unknown";
            }
            long used = gpu.vramTotalMb() - gpu.vramFreeMb();
            double ratio = gpu.vramTotalMb() == 0 ? 0.0 : (double) used / gpu.vramTotalMb();
            if (ratio > 0.98) {
                return "saturated";
            }
            return "ok";
        }

        private String overall(String ecc, String thermal, String memory) {
            if ("critical".equals(thermal) || "false".equalsIgnoreCase(ecc)) {
                return "attention";
            }
            if ("warn".equals(thermal) || "saturated".equals(memory)) {
                return "watch";
            }
            return "ok";
        }
    }

    @Command(name = "set", description = "Apply GPU control settings")
    static class SetCommand implements Callable<Integer> {
        @ParentCommand private GpuCommand gpuCommand;
        @Option(names = "--id", required = true)
        private String id;

        @Option(names = "--power-limit")
        private Integer powerLimit;

        @Option(names = "--clock-fix")
        private String clockFix;

        @Option(names = "--ecc")
        private String ecc;

        @Option(names = "--compute-mode")
        private String computeMode;

        @Override
        public Integer call() {
            CliSupport.require(powerLimit != null || clockFix != null || ecc != null || computeMode != null,
                    "At least one setting must be provided.");
            if (powerLimit != null) {
                CliSupport.requirePositive(powerLimit, "power-limit");
            }
            if (ecc != null) {
                CliSupport.requireOneOf(ecc, "ecc", Set.of("on", "off"));
            }
            if (computeMode != null) {
                CliSupport.requireOneOf(computeMode, "compute-mode", Set.of("default", "exclusive_process"));
            }
            requireKnownGpu(gpuCommand, id);
            String summary = "powerLimit=" + powerLimit + ", clockFix=" + clockFix + ", ecc=" + ecc + ", computeMode=" + computeMode;
            gpuCommand.context().logService().info("gpu", "set", "Recorded GPU control request", id + " " + summary);
            System.out.printf("Recorded GPU control request for %s: %s%n", id, summary);
            return 0;
        }
    }

    @Command(name = "reset", description = "Reset GPU devices")
    static class ResetCommand implements Callable<Integer> {
        @ParentCommand private GpuCommand gpuCommand;
        @Option(names = "--id", required = true)
        private String id;

        @Option(names = "--soft")
        private boolean soft;

        @Option(names = "--hard")
        private boolean hard;

        @Option(names = "--drain-first")
        private boolean drainFirst;

        @Override
        public Integer call() {
            CliSupport.require(soft ^ hard, "Choose exactly one of --soft or --hard");
            requireKnownGpu(gpuCommand, id);
            String mode = soft ? "soft" : "hard";
            gpuCommand.context().logService().warn("gpu", "reset", "Recorded GPU reset request", id + " mode=" + mode + ", drainFirst=" + drainFirst);
            System.out.printf("Recorded %s reset request for GPU %s.%n", mode, id);
            return 0;
        }
    }

    @Command(name = "topology", description = "Show GPU topology")
    static class TopologyCommand implements Callable<Integer> {
        @ParentCommand
        private GpuCommand gpuCommand;

        @Option(names = "--visualize")
        private boolean visualize;

        @Override
        public Integer call() {
            List<GpuDevice> gpus = gpuCommand.context().inventoryRepository().listGpus();
            Map<String, List<GpuDevice>> byNode = new LinkedHashMap<>();
            for (GpuDevice gpu : gpus) {
                byNode.computeIfAbsent(gpu.nodeHostname(), ignored -> new ArrayList<>()).add(gpu);
            }
            if (byNode.isEmpty()) {
                System.out.println("No GPU topology data found.");
                return 0;
            }
            for (Map.Entry<String, List<GpuDevice>> entry : byNode.entrySet()) {
                System.out.println("Node: " + entry.getKey());
                if (visualize) {
                    for (GpuDevice gpu : entry.getValue()) {
                        System.out.printf("  [%s] %s -- %s%n",
                                CliSupport.safe(gpu.deviceId()),
                                CliSupport.safe(gpu.model()),
                                gpu.interconnectType().name());
                    }
                } else {
                    List<String[]> rows = new ArrayList<>();
                    for (GpuDevice gpu : entry.getValue()) {
                        rows.add(new String[]{
                                CliSupport.safe(gpu.deviceId()),
                                CliSupport.safe(gpu.model()),
                                CliSupport.safe(gpu.pciBusId()),
                                gpu.interconnectType().name()
                        });
                    }
                    System.out.println(AsciiTable.getTable(
                            new String[]{"Id", "Model", "PCI", "Link"},
                            rows.toArray(String[][]::new)
                    ));
                }
            }
            return 0;
        }
    }

    private static String format(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f", value);
    }

    private static void requireKnownGpu(GpuCommand gpuCommand, String id) {
        boolean exists = gpuCommand.context().inventoryRepository().listGpus().stream()
                .anyMatch(gpu -> id.equalsIgnoreCase(CliSupport.safe(gpu.deviceId()))
                        || id.equalsIgnoreCase(CliSupport.safe(gpu.uuid()))
                        || id.equalsIgnoreCase(gpu.nodeHostname() + ":" + CliSupport.safe(gpu.deviceId())));
        CliSupport.require(exists, "GPU not found in current inventory: " + id);
    }
}
