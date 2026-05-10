package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.service.FleetAnalysisService;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

/** Exposes full-fleet production analysis commands. */
@Command(
        name = "fleet",
        mixinStandardHelpOptions = true,
        description = "Deep fleet capacity, risk, workload validation, forecast, and doctor reports",
        subcommands = {
                FleetCommand.CapacityCommand.class,
                FleetCommand.RiskCommand.class,
                FleetCommand.ValidateCommand.class,
                FleetCommand.ForecastCommand.class,
                FleetCommand.DoctorCommand.class
        }
)
public class FleetCommand implements Runnable {
    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    FleetAnalysisService service() { return parent.createContext().fleetAnalysisService(); }
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "capacity", description = "Show schedulable capacity, fragmentation, VRAM, power, labels, and model pools")
    static class CapacityCommand implements Callable<Integer> {
        @ParentCommand private FleetCommand fleetCommand;
        @Option(names = "--by-model", description = "Also print capacity grouped by GPU model") private boolean byModel;

        @Override public Integer call() {
            FleetAnalysisService.CapacityReport report = fleetCommand.service().capacity();
            printCapacitySummary(report);
            printNodeCapacity(report.nodes());
            if (byModel) {
                printModelCapacity(report.models());
            }
            return 0;
        }
    }

    @Command(name = "risk", description = "Run a deep read-only production risk analysis")
    static class RiskCommand implements Callable<Integer> {
        @ParentCommand private FleetCommand fleetCommand;
        @Option(names = "--max-scan-age-min", defaultValue = "30") private Integer maxScanAgeMin;
        @Option(names = "--min-free-vram-ratio", defaultValue = "0.05") private Double minFreeVramRatio;
        @Option(names = "--fail-on", defaultValue = "none", description = "none, warn, or critical") private String failOn;

        @Override public Integer call() {
            CliSupport.requirePositive(maxScanAgeMin, "max-scan-age-min");
            CliSupport.require(minFreeVramRatio >= 0.0 && minFreeVramRatio <= 1.0,
                    "min-free-vram-ratio must be between 0 and 1");
            CliSupport.requireOneOf(failOn, "fail-on", Set.of("none", "warn", "critical"));
            FleetAnalysisService.RiskReport report = fleetCommand.service()
                    .risk(new FleetAnalysisService.RiskOptions(maxScanAgeMin, minFreeVramRatio));
            printRisk(report);
            if ("critical".equalsIgnoreCase(failOn) && report.critical() > 0) {
                return 2;
            }
            if ("warn".equalsIgnoreCase(failOn) && (report.critical() > 0 || report.warn() > 0)) {
                return 2;
            }
            return 0;
        }
    }

    @Command(name = "validate", description = "Validate whether a workload can be safely submitted before running gpum submit/job")
    static class ValidateCommand implements Callable<Integer> {
        @ParentCommand private FleetCommand fleetCommand;
        @Option(names = "--gpus", required = true) private Integer gpus;
        @Option(names = "--vram") private Long vram;
        @Option(names = "--hours", defaultValue = "1") private Integer hours;
        @Option(names = "--cpu-cores") private Integer cpuCores;
        @Option(names = "--memory-mb") private Long memoryMb;
        @Option(names = "--shm-size") private String shmSize;
        @Option(names = "--model") private String model;
        @Option(names = "--label-selector") private String labelSelector;
        @Option(names = "--strategy", defaultValue = "packed", description = "packed or spread") private String strategy;
        @Option(names = "--image") private String image;
        @Option(names = "--command") private String command;
        @Option(names = "--fail-on-warn") private boolean failOnWarn;

        @Override public Integer call() {
            CliSupport.requirePositive(gpus, "gpus");
            CliSupport.requirePositive(hours, "hours");
            if (vram != null) {
                CliSupport.requirePositiveLong(vram, "vram");
            }
            if (cpuCores != null) {
                CliSupport.requirePositive(cpuCores, "cpu-cores");
            }
            if (memoryMb != null) {
                CliSupport.requirePositiveLong(memoryMb, "memory-mb");
            }
            CliSupport.requireOneOf(strategy, "strategy", Set.of("packed", "spread"));
            FleetAnalysisService.WorkloadValidationResult result = fleetCommand.service().validateWorkload(
                    new FleetAnalysisService.WorkloadValidationRequest(
                            gpus,
                            vram,
                            hours,
                            cpuCores,
                            memoryMb,
                            parseSizeMb(shmSize),
                            model,
                            labelSelector,
                            strategy,
                            image,
                            command
                    )
            );
            printValidation(result);
            if (!result.allowed()) {
                return 2;
            }
            return failOnWarn && result.hasWarnings() ? 2 : 0;
        }
    }

    @Command(name = "forecast", description = "Forecast GPU-hour runway and daily job throughput")
    static class ForecastCommand implements Callable<Integer> {
        @ParentCommand private FleetCommand fleetCommand;
        @Option(names = "--days", defaultValue = "7") private Integer days;
        @Option(names = "--target-utilization", defaultValue = "0.70") private Double targetUtilization;
        @Option(names = "--reserve-ratio", defaultValue = "0.20") private Double reserveRatio;
        @Option(names = "--job-gpu-hours", defaultValue = "8.0") private Double jobGpuHours;
        @Option(names = "--jobs-per-day", defaultValue = "1") private Integer jobsPerDay;

        @Override public Integer call() {
            CliSupport.requirePositive(days, "days");
            CliSupport.require(jobGpuHours > 0.0, "job-gpu-hours must be > 0");
            CliSupport.requirePositive(jobsPerDay, "jobs-per-day");
            CliSupport.require(targetUtilization > 0.0 && targetUtilization <= 1.0,
                    "target-utilization must be > 0 and <= 1");
            CliSupport.require(reserveRatio >= 0.0 && reserveRatio < 1.0,
                    "reserve-ratio must be >= 0 and < 1");
            FleetAnalysisService.ForecastReport report = fleetCommand.service().forecast(
                    new FleetAnalysisService.ForecastRequest(days, targetUtilization, reserveRatio, jobGpuHours, jobsPerDay)
            );
            printForecast(report);
            return 0;
        }
    }

    @Command(name = "doctor", description = "Print a combined production readiness report")
    static class DoctorCommand implements Callable<Integer> {
        @ParentCommand private FleetCommand fleetCommand;
        @Option(names = "--max-scan-age-min", defaultValue = "30") private Integer maxScanAgeMin;
        @Option(names = "--fail-on-critical") private boolean failOnCritical;

        @Override public Integer call() {
            CliSupport.requirePositive(maxScanAgeMin, "max-scan-age-min");
            FleetAnalysisService service = fleetCommand.service();
            FleetAnalysisService.CapacityReport capacity = service.capacity();
            FleetAnalysisService.RiskReport risk = service.risk(new FleetAnalysisService.RiskOptions(maxScanAgeMin, 0.05));
            FleetAnalysisService.ForecastReport forecast = service.forecast(
                    new FleetAnalysisService.ForecastRequest(7, 0.70, 0.20, 8.0, 1)
            );
            System.out.println("== Capacity ==");
            printCapacitySummary(capacity);
            System.out.println("== Top Risks ==");
            printRiskTop(risk, 12);
            System.out.println("== Seven Day Forecast ==");
            printForecast(forecast);
            System.out.printf("Doctor summary: critical=%d warn=%d status=%s%n",
                    risk.critical(), risk.warn(), forecast.status());
            return failOnCritical && risk.critical() > 0 ? 2 : 0;
        }
    }

    private static void printCapacitySummary(FleetAnalysisService.CapacityReport report) {
        FleetAnalysisService.CapacityTotals totals = report.totals();
        System.out.println(AsciiTable.getTable(new String[]{"Metric", "Value"}, new String[][]{
                {"nodes", Integer.toString(totals.nodes())},
                {"readyNodes", Long.toString(totals.readyNodes())},
                {"totalGpus", Integer.toString(totals.totalGpus())},
                {"allocatedGpus", Integer.toString(totals.allocatedGpus())},
                {"quarantinedGpus", Integer.toString(totals.quarantinedGpus())},
                {"allocatableGpus", Integer.toString(totals.allocatableGpus())},
                {"freeGpus", Integer.toString(totals.freeGpus())},
                {"totalVramGb", Long.toString(totals.totalVramMb() / 1024L)},
                {"freeVramGb", Long.toString(totals.freeVramMb() / 1024L)},
                {"powerUsageW", fmt(totals.powerUsageW())},
                {"powerLimitW", fmt(totals.powerLimitW())}
        }));
    }

    private static void printNodeCapacity(List<FleetAnalysisService.NodeCapacity> nodes) {
        List<String[]> rows = new ArrayList<>();
        for (FleetAnalysisService.NodeCapacity node : nodes) {
            rows.add(new String[]{
                    node.node(),
                    node.state(),
                    Integer.toString(node.totalGpus()),
                    Integer.toString(node.allocatedGpus()),
                    Integer.toString(node.quarantinedGpus()),
                    Integer.toString(node.freeGpus()),
                    Long.toString(node.totalVramMb() / 1024L),
                    Long.toString(node.freeVramMb() / 1024L),
                    fmt(node.avgUtilization()),
                    fmt(node.maxTemperatureC()),
                    node.topologyClass(),
                    emptyDash(String.join(",", node.labels())),
                    emptyDash(String.join(",", node.blockers()))
            });
        }
        System.out.println(AsciiTable.getTable(
                new String[]{"Node", "State", "GPU", "Alloc", "Q", "Free", "VRAMGB", "FreeGB", "Util", "Temp", "Topo", "Labels", "Blockers"},
                rows.toArray(String[][]::new)
        ));
    }

    private static void printModelCapacity(List<FleetAnalysisService.ModelCapacity> models) {
        List<String[]> rows = new ArrayList<>();
        for (FleetAnalysisService.ModelCapacity model : models) {
            rows.add(new String[]{
                    model.vendor(),
                    model.model(),
                    Integer.toString(model.total()),
                    Integer.toString(model.allocated()),
                    Integer.toString(model.blocked()),
                    Integer.toString(model.free()),
                    Long.toString(model.totalVramMb() / 1024L)
            });
        }
        System.out.println(AsciiTable.getTable(
                new String[]{"Vendor", "Model", "Total", "Allocated", "Blocked", "Free", "VRAMGB"},
                rows.toArray(String[][]::new)
        ));
    }

    private static void printRisk(FleetAnalysisService.RiskReport report) {
        printRiskTop(report, report.findings().size());
        System.out.printf("Risk summary: critical=%d warn=%d ok=%d%n", report.critical(), report.warn(), report.ok());
    }

    private static void printRiskTop(FleetAnalysisService.RiskReport report, int limit) {
        List<String[]> rows = new ArrayList<>();
        int count = 0;
        for (FleetAnalysisService.RiskFinding finding : report.findings()) {
            if (count++ >= limit) {
                break;
            }
            rows.add(new String[]{
                    finding.severity(),
                    finding.category(),
                    finding.scope(),
                    finding.detail(),
                    finding.action()
            });
        }
        System.out.println(AsciiTable.getTable(
                new String[]{"Severity", "Category", "Scope", "Detail", "Action"},
                rows.toArray(String[][]::new)
        ));
    }

    private static void printValidation(FleetAnalysisService.WorkloadValidationResult result) {
        List<String[]> rows = new ArrayList<>();
        for (FleetAnalysisService.ValidationCheck check : result.checks()) {
            rows.add(new String[]{check.severity(), check.name(), check.message(), check.action()});
        }
        System.out.printf("Validation allowed=%s selectedNode=%s warnings=%s%n",
                result.allowed(), CliSupport.safe(result.selectedNode()), result.hasWarnings());
        System.out.println(AsciiTable.getTable(
                new String[]{"Severity", "Check", "Message", "Action"},
                rows.toArray(String[][]::new)
        ));
    }

    private static void printForecast(FleetAnalysisService.ForecastReport report) {
        System.out.println(AsciiTable.getTable(new String[]{"Metric", "Value"}, new String[][]{
                {"status", report.status()},
                {"days", Integer.toString(report.days())},
                {"freeGpus", Integer.toString(report.freeGpus())},
                {"targetUtilization", fmt(report.targetUtilization())},
                {"reserveRatio", fmt(report.reserveRatio())},
                {"jobGpuHours", fmt(report.jobGpuHours())},
                {"jobsPerDay", Integer.toString(report.jobsPerDay())},
                {"usableGpuHoursPerDay", fmt(report.usableGpuHoursPerDay())},
                {"requestedGpuHoursPerDay", fmt(report.requestedGpuHoursPerDay())},
                {"usableGpuHoursForWindow", fmt(report.usableGpuHoursForWindow())},
                {"maxJobsPerDay", Integer.toString(report.maxJobsPerDay())},
                {"dailyBalanceGpuHours", fmt(report.dailyBalanceGpuHours())},
                {"recommendation", report.recommendation()}
        }));
    }

    private static Long parseSizeMb(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1L;
        if (normalized.endsWith("gb")) {
            multiplier = 1024L;
            normalized = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("g")) {
            multiplier = 1024L;
            normalized = normalized.substring(0, normalized.length() - 1);
        } else if (normalized.endsWith("mb")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("m")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            long amount = Long.parseLong(normalized.trim());
            CliSupport.require(amount > 0L, "shm-size must be > 0");
            return amount * multiplier;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("shm-size must be a number with optional m/mb/g/gb suffix");
        }
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String emptyDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
