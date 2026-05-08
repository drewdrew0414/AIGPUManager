package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.AppContext;
import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuHealthScore;
import com.drewdrew1.core.service.GpuControlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

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
            CliSupport.requireNotDirectory(path, "export path");
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
            CliSupport.writeLinesAtomic(path, lines);
        }

        private void exportInflux(Path path, List<GpuDevice> gpus) throws Exception {
            CliSupport.requireNotDirectory(path, "export path");
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
            CliSupport.writeLinesAtomic(path, lines);
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
        @Option(names = "--score")
        private boolean score;
        @Option(names = "--quarantine-threshold", description = "Mark GPUs below this score as unschedulable")
        private Double quarantineThreshold;

        @Override
        public Integer call() {
            List<GpuDevice> gpus = gpuCommand.context().inventoryRepository().listGpus();
            Map<String, GpuHealthScore> scores = new LinkedHashMap<>();
            for (GpuHealthScore item : gpuCommand.context().healthScoringService().scoreAll()) {
                scores.put(item.nodeHostname() + ":" + CliSupport.safe(item.deviceId()), item);
            }
            List<String[]> rows = new ArrayList<>();
            for (GpuDevice gpu : gpus) {
                String ecc = checkEcc ? String.valueOf(gpu.eccEnabled()) : "skipped";
                String thermal = thermalTest ? assessThermal(gpu) : "skipped";
                String memory = memoryTest ? assessMemory(gpu) : "skipped";
                String overall = overall(ecc, thermal, memory);
                GpuHealthScore healthScore = scores.get(gpu.nodeHostname() + ":" + CliSupport.safe(gpu.deviceId()));
                rows.add(new String[]{
                        CliSupport.safe(gpu.nodeHostname()),
                        CliSupport.safe(gpu.deviceId()),
                        CliSupport.safe(gpu.model()),
                        ecc,
                        thermal,
                        memory,
                        overall,
                        score && healthScore != null ? String.format(Locale.ROOT, "%.1f", healthScore.score()) : "-",
                        healthScore != null && healthScore.quarantineRecommended() ? "yes" : "no"
                });
            }
            System.out.println(AsciiTable.getTable(
                    new String[]{"Node", "Id", "Model", "ECC", "Thermal", "Memory", "Overall", "Score", "Quarantine"},
                    rows.toArray(String[][]::new)
            ));
            if (quarantineThreshold != null) {
                CliSupport.require(quarantineThreshold >= 0.0 && quarantineThreshold <= 100.0,
                        "quarantine-threshold must be between 0 and 100");
                int changed = gpuCommand.context().healthScoringService().applyQuarantine(quarantineThreshold);
                System.out.printf("Applied GPU quarantine to %d device(s) below score %.1f.%n", changed, quarantineThreshold);
            }
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

        @Option(names = "--apply", description = "Execute guarded vendor hardware writes")
        private boolean apply;

        @Option(names = "--allow-allocated", description = "Allow writes even when the GPU has an active allocation")
        private boolean allowAllocated;

        @Option(names = "--allow-reboot-required", description = "Allow operations that require a reset or reboot to take effect")
        private boolean allowRebootRequired;

        @Option(names = "--via-agent", description = "Route non-local apply through remote gpum over SSH")
        private boolean viaAgent;

        @Option(names = "--ssh-user", description = "Override SSH user for --via-agent")
        private String sshUser;

        @Option(names = "--approval-id", description = "Approved request id for high-risk apply")
        private String approvalId;

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
            GpuDevice gpu = apply ? requireKnownGpu(gpuCommand, id) : requireAnyKnownGpu(gpuCommand, id);
            String summary = "powerLimit=" + powerLimit + ", clockFix=" + clockFix + ", ecc=" + ecc + ", computeMode=" + computeMode;
            if (apply) {
                var approval = gpuCommand.context().accessControlService().requireApprovalOrSubmit(
                        CliSupport.currentActor(),
                        null,
                        "GPU_SET_APPLY",
                        "GPU",
                        gpu.nodeHostname() + ":" + CliSupport.safe(gpu.deviceId()),
                        summary + ", viaAgent=" + viaAgent,
                        com.drewdrew1.core.model.RbacRole.APPROVER,
                        approvalId
                );
                if (approval != null) {
                    System.out.printf("GPU apply requires approval. Request created: %s%n", approval.id());
                    return 0;
                }
                gpuCommand.context().accessControlService().requireRole(
                        CliSupport.currentActor(),
                        com.drewdrew1.core.model.RbacRole.OPERATOR,
                        null,
                        "OPERATOR role is required to apply GPU control changes."
                );
                if (viaAgent) {
                    var result = gpuCommand.context().remoteGpuControlService().applySettings(
                            gpu,
                            new GpuControlService.SetRequest(apply, allowAllocated, allowRebootRequired, powerLimit, clockFix, ecc, computeMode),
                            sshUser
                    );
                    gpuCommand.context().auditService().log("GPU_SET_APPLY_REMOTE", CliSupport.currentActor(), id, summary + ", remote=" + result.address());
                    gpuCommand.context().logService().info("gpu", "set", "Applied remote GPU control request", id + " " + summary);
                    System.out.printf("Applied remote GPU control request for %s via %s@%s.%n", id, result.sshUser(), result.address());
                    System.out.println("Executed: " + String.join(" ", result.command()));
                    if (result.result().stdout() != null && !result.result().stdout().isBlank()) {
                        System.out.println(result.result().stdout().trim());
                    }
                    return 0;
                }
                GpuControlService.ControlResult result = gpuCommand.context().gpuControlService().applySettings(
                        gpu,
                        new GpuControlService.SetRequest(apply, allowAllocated, allowRebootRequired, powerLimit, clockFix, ecc, computeMode)
                );
                gpuCommand.context().auditService().log("GPU_SET_APPLY", CliSupport.currentActor(), id, summary);
                gpuCommand.context().logService().info("gpu", "set", "Applied GPU control request", id + " " + summary);
                System.out.printf("Applied GPU control request for %s (%s).%n", id, result.vendor());
                for (List<String> command : result.commands()) {
                    System.out.println("Executed: " + String.join(" ", command));
                }
                return 0;
            }
            gpuCommand.context().logService().info("gpu", "set", "Recorded GPU control request", id + " " + summary);
            GpuControlService.ControlPreview preview = gpuCommand.context().gpuControlService().previewSettings(
                    gpu,
                    new GpuControlService.SetRequest(false, allowAllocated, allowRebootRequired, powerLimit, clockFix, ecc, computeMode)
            );
            printPreview("GPU set dry-run", id, summary, preview);
            if (viaAgent) {
                try {
                    List<String> remoteCommand = buildRemoteSetCommand(gpuCommand, gpu, allowAllocated, allowRebootRequired, powerLimit, clockFix, ecc, computeMode);
                    System.out.println("Remote agent path:");
                    System.out.println("- " + String.join(" ", gpuCommand.context().remoteGpuControlService().previewCommand(gpu, remoteCommand, sshUser)));
                } catch (Exception e) {
                    System.out.println("Remote agent blocker:");
                    System.out.println("- " + e.getMessage());
                }
            }
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

        @Option(names = "--apply", description = "Execute guarded vendor hardware reset")
        private boolean apply;

        @Option(names = "--allow-linked-reset", description = "Permit reset of non-PCIe linked GPUs after explicit review")
        private boolean allowLinkedReset;

        @Option(names = "--via-agent", description = "Route non-local apply through remote gpum over SSH")
        private boolean viaAgent;

        @Option(names = "--ssh-user", description = "Override SSH user for --via-agent")
        private String sshUser;

        @Option(names = "--approval-id", description = "Approved request id for high-risk reset")
        private String approvalId;

        @Override
        public Integer call() {
            CliSupport.require(soft ^ hard, "Choose exactly one of --soft or --hard");
            GpuDevice gpu = apply ? requireKnownGpu(gpuCommand, id) : requireAnyKnownGpu(gpuCommand, id);
            String mode = soft ? "soft" : "hard";
            if (apply) {
                var approval = gpuCommand.context().accessControlService().requireApprovalOrSubmit(
                        CliSupport.currentActor(),
                        null,
                        "GPU_RESET_APPLY",
                        "GPU",
                        gpu.nodeHostname() + ":" + CliSupport.safe(gpu.deviceId()),
                        "mode=" + mode + ", drainFirst=" + drainFirst + ", viaAgent=" + viaAgent,
                        com.drewdrew1.core.model.RbacRole.APPROVER,
                        approvalId
                );
                if (approval != null) {
                    System.out.printf("GPU reset requires approval. Request created: %s%n", approval.id());
                    return 0;
                }
                gpuCommand.context().accessControlService().requireRole(
                        CliSupport.currentActor(),
                        com.drewdrew1.core.model.RbacRole.OPERATOR,
                        null,
                        "OPERATOR role is required to apply GPU resets."
                );
                if (drainFirst) {
                    String localHost = gpuCommand.context().systemInfoService().localNodeInventory().hostname();
                    CliSupport.require(viaAgent || localHost.equalsIgnoreCase(gpu.nodeHostname()),
                            "drain-first is only supported for GPUs on the local node");
                    String hostToDrain = viaAgent ? gpu.nodeHostname() : localHost;
                    gpuCommand.context().inventoryRepository().putNodeAttribute(hostToDrain, "state.drained", "true");
                    gpuCommand.context().inventoryRepository().putNodeAttribute(hostToDrain, "state.drained.reason", "gpu reset");
                }
                if (viaAgent) {
                    var result = gpuCommand.context().remoteGpuControlService().resetGpu(
                            gpu,
                            new GpuControlService.ResetRequest(apply, hard, allowLinkedReset),
                            sshUser,
                            drainFirst
                    );
                    gpuCommand.context().auditService().log("GPU_RESET_APPLY_REMOTE", CliSupport.currentActor(), id, "mode=" + mode);
                    gpuCommand.context().logService().warn("gpu", "reset", "Applied remote GPU reset request", id + " mode=" + mode);
                    System.out.printf("Applied remote %s reset for GPU %s via %s@%s.%n", mode, id, result.sshUser(), result.address());
                    System.out.println("Executed: " + String.join(" ", result.command()));
                    if (result.result().stdout() != null && !result.result().stdout().isBlank()) {
                        System.out.println(result.result().stdout().trim());
                    }
                    return 0;
                }
                GpuControlService.ControlResult result = gpuCommand.context().gpuControlService().resetGpu(
                        gpu,
                        new GpuControlService.ResetRequest(apply, hard, allowLinkedReset)
                );
                gpuCommand.context().auditService().log("GPU_RESET_APPLY", CliSupport.currentActor(), id, "mode=" + mode);
                gpuCommand.context().logService().warn("gpu", "reset", "Applied GPU reset request", id + " mode=" + mode + ", drainFirst=" + drainFirst);
                System.out.printf("Applied %s reset for GPU %s.%n", mode, id);
                for (List<String> command : result.commands()) {
                    System.out.println("Executed: " + String.join(" ", command));
                }
                return 0;
            }
            gpuCommand.context().logService().warn("gpu", "reset", "Recorded GPU reset request", id + " mode=" + mode + ", drainFirst=" + drainFirst);
            GpuControlService.ControlPreview preview = gpuCommand.context().gpuControlService().previewReset(
                    gpu,
                    new GpuControlService.ResetRequest(false, hard, allowLinkedReset)
            );
            printPreview("GPU reset dry-run", id, "mode=" + mode + ", drainFirst=" + drainFirst, preview);
            if (viaAgent) {
                try {
                    List<String> remoteCommand = buildRemoteResetCommand(gpuCommand, gpu, hard, allowLinkedReset, drainFirst);
                    System.out.println("Remote agent path:");
                    System.out.println("- " + String.join(" ", gpuCommand.context().remoteGpuControlService().previewCommand(gpu, remoteCommand, sshUser)));
                } catch (Exception e) {
                    System.out.println("Remote agent blocker:");
                    System.out.println("- " + e.getMessage());
                }
            }
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

    private static GpuDevice requireKnownGpu(GpuCommand gpuCommand, String id) {
        List<GpuDevice> matches = gpuCommand.context().inventoryRepository().listGpus().stream()
                .filter(gpu -> id.equalsIgnoreCase(CliSupport.safe(gpu.deviceId()))
                        || id.equalsIgnoreCase(CliSupport.safe(gpu.uuid()))
                        || id.equalsIgnoreCase(gpu.nodeHostname() + ":" + CliSupport.safe(gpu.deviceId())))
                .toList();
        CliSupport.require(!matches.isEmpty(), "GPU not found in current inventory: " + id);
        CliSupport.require(matches.size() == 1, "GPU selector is ambiguous: " + id + ". Use node:deviceId or UUID.");
        return matches.getFirst();
    }

    private static GpuDevice requireAnyKnownGpu(GpuCommand gpuCommand, String id) {
        List<GpuDevice> matches = gpuCommand.context().inventoryRepository().listGpus().stream()
                .filter(gpu -> id.equalsIgnoreCase(CliSupport.safe(gpu.deviceId()))
                        || id.equalsIgnoreCase(CliSupport.safe(gpu.uuid()))
                        || id.equalsIgnoreCase(gpu.nodeHostname() + ":" + CliSupport.safe(gpu.deviceId())))
                .toList();
        CliSupport.require(!matches.isEmpty(), "GPU not found in current inventory: " + id);
        return matches.getFirst();
    }

    private static void printPreview(String title, String id, String summary, GpuControlService.ControlPreview preview) {
        System.out.println(title + ":");
        System.out.println("Target: " + id);
        System.out.println("Summary: " + summary);
        System.out.println("Vendor: " + preview.vendor());
        System.out.println("Node: " + CliSupport.safe(preview.nodeHostname()));
        if (!preview.commands().isEmpty()) {
            System.out.println("Planned commands:");
            for (List<String> command : preview.commands()) {
                System.out.println("- " + String.join(" ", command));
            }
        } else {
            System.out.println("Planned commands: none");
        }
        if (!preview.blockers().isEmpty()) {
            System.out.println("Blockers:");
            for (String blocker : preview.blockers()) {
                System.out.println("- " + blocker);
            }
        }
        if (!preview.notes().isEmpty()) {
            System.out.println("Notes:");
            for (String note : preview.notes()) {
                System.out.println("- " + note);
            }
        }
    }

    private static List<String> buildRemoteSetCommand(
            GpuCommand gpuCommand,
            GpuDevice gpu,
            boolean allowAllocated,
            boolean allowRebootRequired,
            Integer powerLimit,
            String clockFix,
            String ecc,
            String computeMode
    ) {
        List<String> command = new ArrayList<>();
        command.add("env");
        command.add("GPUM_ENABLE_HARDWARE_WRITE=1");
        command.add(gpuCommand.context().config().getTools().getGpumAgentCommand());
        command.add("gpu");
        command.add("set");
        command.add("--id");
        command.add(gpu.uuid() != null && !gpu.uuid().isBlank() ? gpu.uuid() : gpu.deviceId());
        if (powerLimit != null) {
            command.add("--power-limit");
            command.add(Integer.toString(powerLimit));
        }
        if (clockFix != null) {
            command.add("--clock-fix");
            command.add(clockFix);
        }
        if (ecc != null) {
            command.add("--ecc");
            command.add(ecc);
        }
        if (computeMode != null) {
            command.add("--compute-mode");
            command.add(computeMode);
        }
        if (allowAllocated) {
            command.add("--allow-allocated");
        }
        if (allowRebootRequired) {
            command.add("--allow-reboot-required");
        }
        command.add("--apply");
        return command;
    }

    private static List<String> buildRemoteResetCommand(
            GpuCommand gpuCommand,
            GpuDevice gpu,
            boolean hard,
            boolean allowLinkedReset,
            boolean drainFirst
    ) {
        List<String> command = new ArrayList<>();
        command.add("env");
        command.add("GPUM_ENABLE_HARDWARE_WRITE=1");
        command.add(gpuCommand.context().config().getTools().getGpumAgentCommand());
        command.add("gpu");
        command.add("reset");
        command.add("--id");
        command.add(gpu.uuid() != null && !gpu.uuid().isBlank() ? gpu.uuid() : gpu.deviceId());
        command.add(hard ? "--hard" : "--soft");
        if (allowLinkedReset) {
            command.add("--allow-linked-reset");
        }
        if (drainFirst) {
            command.add("--drain-first");
        }
        command.add("--apply");
        return command;
    }
}
