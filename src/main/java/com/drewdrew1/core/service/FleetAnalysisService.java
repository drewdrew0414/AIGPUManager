package com.drewdrew1.core.service;

import com.drewdrew1.core.config.GpumConfig;
import com.drewdrew1.core.model.AllocationDevice;
import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuHealthScore;
import com.drewdrew1.core.model.InterconnectType;
import com.drewdrew1.core.model.NodeInventory;
import com.drewdrew1.core.model.OpsRecord;
import com.drewdrew1.core.model.RuntimeWorkerRecord;
import com.drewdrew1.core.repository.AllocationRepository;
import com.drewdrew1.core.repository.InventoryRepository;
import com.drewdrew1.core.repository.OpsRepository;
import com.drewdrew1.core.repository.RuntimeRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds read-only production reports across inventory, allocations, runtime workers, and guardrail records. */
public class FleetAnalysisService {
    private final InventoryRepository inventoryRepository;
    private final AllocationRepository allocationRepository;
    private final OpsRepository opsRepository;
    private final RuntimeRepository runtimeRepository;
    private final HealthScoringService healthScoringService;
    private final GpumConfig config;

    public FleetAnalysisService(
            InventoryRepository inventoryRepository,
            AllocationRepository allocationRepository,
            OpsRepository opsRepository,
            RuntimeRepository runtimeRepository,
            HealthScoringService healthScoringService,
            GpumConfig config
    ) {
        this.inventoryRepository = inventoryRepository;
        this.allocationRepository = allocationRepository;
        this.opsRepository = opsRepository;
        this.runtimeRepository = runtimeRepository;
        this.healthScoringService = healthScoringService;
        this.config = config;
    }

    public CapacityReport capacity() {
        initializeReadModels();
        List<NodeInventory> nodes = inventoryRepository.listNodes();
        List<GpuDevice> gpus = inventoryRepository.listGpus();
        List<AllocationRecord> active = allocationRepository.listActive();
        Map<String, Map<String, String>> attributes = inventoryRepository.listNodeAttributes();
        Map<String, List<GpuDevice>> gpusByNode = gpus.stream()
                .collect(Collectors.groupingBy(GpuDevice::nodeHostname, LinkedHashMap::new, Collectors.toList()));
        Map<String, Integer> allocatedByNode = allocatedGpuCountByNode(active);

        List<NodeCapacity> nodeRows = new ArrayList<>();
        for (NodeInventory node : nodes) {
            List<GpuDevice> nodeGpus = gpusByNode.getOrDefault(node.hostname(), List.of());
            Map<String, String> nodeAttrs = attributes.getOrDefault(node.hostname(), Map.of());
            List<String> blockers = blockers(nodeAttrs);
            boolean schedulable = blockers.isEmpty();
            int quarantined = countQuarantined(nodeGpus, nodeAttrs);
            int allocated = allocatedByNode.getOrDefault(node.hostname(), 0);
            int allocatable = schedulable ? Math.max(0, nodeGpus.size() - quarantined) : 0;
            int free = Math.max(0, allocatable - allocated);
            nodeRows.add(new NodeCapacity(
                    node.hostname(),
                    schedulable ? "READY" : "BLOCKED",
                    node.cpuCores(),
                    node.memoryTotalMb(),
                    nodeGpus.size(),
                    allocated,
                    quarantined,
                    allocatable,
                    free,
                    sumLong(nodeGpus, GpuDevice::vramTotalMb),
                    sumLong(nodeGpus, GpuDevice::vramFreeMb),
                    averageDouble(nodeGpus, GpuDevice::utilizationGpu),
                    maxDouble(nodeGpus, GpuDevice::temperatureC),
                    sumDouble(nodeGpus, GpuDevice::powerUsageW),
                    sumDouble(nodeGpus, GpuDevice::powerLimitW),
                    dominantModel(nodeGpus),
                    topologyClass(nodeGpus),
                    labels(nodeAttrs),
                    blockers,
                    node.lastScannedAt()
            ));
        }
        nodeRows.sort(Comparator
                .comparing(NodeCapacity::state)
                .thenComparing(Comparator.comparingInt(NodeCapacity::freeGpus).reversed())
                .thenComparing(NodeCapacity::node));

        CapacityTotals totals = new CapacityTotals(
                nodeRows.size(),
                nodeRows.stream().filter(row -> "READY".equals(row.state())).count(),
                nodeRows.stream().mapToInt(NodeCapacity::totalGpus).sum(),
                nodeRows.stream().mapToInt(NodeCapacity::allocatedGpus).sum(),
                nodeRows.stream().mapToInt(NodeCapacity::quarantinedGpus).sum(),
                nodeRows.stream().mapToInt(NodeCapacity::allocatableGpus).sum(),
                nodeRows.stream().mapToInt(NodeCapacity::freeGpus).sum(),
                nodeRows.stream().mapToLong(NodeCapacity::totalVramMb).sum(),
                nodeRows.stream().mapToLong(NodeCapacity::freeVramMb).sum(),
                nodeRows.stream().mapToDouble(NodeCapacity::powerUsageW).sum(),
                nodeRows.stream().mapToDouble(NodeCapacity::powerLimitW).sum()
        );
        return new CapacityReport(totals, nodeRows, modelCapacity(gpus, active, attributes));
    }

    public RiskReport risk(RiskOptions options) {
        initializeReadModels();
        RiskOptions effective = options == null ? RiskOptions.defaults() : options;
        SafetyPolicy safetyPolicy = safetyPolicy();
        List<RiskFinding> findings = new ArrayList<>();
        List<NodeInventory> nodes = inventoryRepository.listNodes();
        List<GpuDevice> gpus = inventoryRepository.listGpus();
        List<AllocationRecord> active = allocationRepository.listActive();
        Map<String, Map<String, String>> attributes = inventoryRepository.listNodeAttributes();
        Instant now = Instant.now();

        if (nodes.isEmpty()) {
            findings.add(new RiskFinding("CRITICAL", "inventory", "cluster", "no nodes found", "Run gpum node scan before scheduling jobs."));
        }
        if (gpus.isEmpty()) {
            findings.add(new RiskFinding("WARN", "inventory", "cluster", "no GPUs found", "Confirm hardware tools and detector configuration."));
        }

        for (NodeInventory node : nodes) {
            long scanAgeMinutes = Duration.between(node.lastScannedAt(), now).toMinutes();
            if (scanAgeMinutes > effective.maxScanAgeMinutes() * 4L) {
                findings.add(new RiskFinding("CRITICAL", "inventory", node.hostname(),
                        "scan is very stale: " + scanAgeMinutes + "m",
                        "Refresh inventory before allocation."));
            } else if (scanAgeMinutes > effective.maxScanAgeMinutes()) {
                findings.add(new RiskFinding("WARN", "inventory", node.hostname(),
                        "scan is stale: " + scanAgeMinutes + "m",
                        "Run gpum node scan --force for this node."));
            }
            Map<String, String> nodeAttrs = attributes.getOrDefault(node.hostname(), Map.of());
            for (String blocker : blockers(nodeAttrs)) {
                findings.add(new RiskFinding(
                        blocker.contains("quarantined") ? "CRITICAL" : "WARN",
                        "scheduling",
                        node.hostname(),
                        blocker,
                        "Resolve the node state or keep it out of placement."
                ));
            }
            if (node.cpuCores() <= 0 || node.memoryTotalMb() <= 0) {
                findings.add(new RiskFinding("CRITICAL", "inventory", node.hostname(),
                        "node CPU or memory inventory is invalid",
                        "Fix node discovery before admitting jobs."));
            }
        }

        Map<String, GpuHealthScore> scores = healthScoringService.scoreAll().stream()
                .collect(Collectors.toMap(score -> score.nodeHostname() + ":" + score.deviceId(), score -> score, (a, b) -> a, LinkedHashMap::new));
        for (GpuDevice gpu : gpus) {
            String target = gpu.nodeHostname() + ":" + safe(gpu.deviceId());
            GpuHealthScore score = scores.get(target);
            if (score != null && score.quarantineRecommended()) {
                findings.add(new RiskFinding("CRITICAL", "gpu-health", target,
                        "health score " + format(score.score()) + " reasons=" + String.join(",", score.reasons()),
                        "Quarantine or drain before scheduling more work."));
            } else if (score != null && score.degraded()) {
                findings.add(new RiskFinding("WARN", "gpu-health", target,
                        "health score " + format(score.score()) + " reasons=" + String.join(",", score.reasons()),
                        "Prefer lower priority work or inspect the device."));
            }
            if (gpu.temperatureC() != null && gpu.temperatureC() >= safetyPolicy.thermalCriticalC()) {
                findings.add(new RiskFinding("CRITICAL", "thermal", target,
                        "temperature=" + format(gpu.temperatureC()) + "C",
                        "Stop new work and inspect cooling."));
            } else if (gpu.temperatureC() != null && gpu.temperatureC() >= safetyPolicy.thermalWarnC()) {
                findings.add(new RiskFinding("WARN", "thermal", target,
                        "temperature=" + format(gpu.temperatureC()) + "C",
                        "Avoid high-power placements until temperature drops."));
            }
            if (gpu.powerUsageW() != null && gpu.powerLimitW() != null && gpu.powerLimitW() > 0.0) {
                double ratio = gpu.powerUsageW() / gpu.powerLimitW();
                if (ratio >= 1.0) {
                    findings.add(new RiskFinding("CRITICAL", "power", target,
                            "power is at or above limit ratio=" + format(ratio),
                            "Preempt workload or reduce power cap pressure."));
                } else if (ratio >= 0.95) {
                    findings.add(new RiskFinding("WARN", "power", target,
                            "power is near limit ratio=" + format(ratio),
                            "Avoid co-locating another high draw job."));
                }
            }
            if (gpu.vramTotalMb() != null && gpu.vramFreeMb() != null && gpu.vramTotalMb() > 0) {
                double freeRatio = (double) gpu.vramFreeMb() / gpu.vramTotalMb();
                if (freeRatio < effective.minFreeVramRatio()) {
                    findings.add(new RiskFinding(freeRatio <= 0.01 ? "CRITICAL" : "WARN", "memory", target,
                            "free VRAM ratio=" + format(freeRatio),
                            "Run zombie cleanup or stop the leaking workload."));
                }
            }
            if (gpu.uuid() == null || gpu.uuid().isBlank()) {
                findings.add(new RiskFinding("WARN", "inventory", target,
                        "GPU UUID is missing",
                        "Refresh inventory; UUID-based allocation is safer than index-based allocation."));
            }
        }

        for (AllocationRecord allocation : active) {
            if (allocation.expiresAt().isBefore(now)) {
                findings.add(new RiskFinding("CRITICAL", "allocation", allocation.id(),
                        "active allocation is expired",
                        "Run gpum alloc reap or release the allocation."));
            }
            if (allocation.devices().isEmpty()) {
                findings.add(new RiskFinding("CRITICAL", "allocation", allocation.id(),
                        "active allocation has no devices",
                        "Release and recreate the allocation."));
            }
            if (allocation.requestedGpuCount() > safetyPolicy.maxGpusPerRequest()) {
                findings.add(new RiskFinding("CRITICAL", "quota", allocation.id(),
                        "requested GPUs exceed safety policy",
                        "Split the job or raise policy through approval."));
            }
            long leaseHours = Math.max(0L, Duration.between(allocation.createdAt(), allocation.expiresAt()).toHours());
            if (leaseHours > safetyPolicy.maxLeaseHours()) {
                findings.add(new RiskFinding("WARN", "quota", allocation.id(),
                        "lease is longer than safety policy: " + leaseHours + "h",
                        "Shorten lease or require explicit approval."));
            }
            for (AllocationDevice device : allocation.devices()) {
                Map<String, String> nodeAttrs = attributes.getOrDefault(device.nodeHostname(), Map.of());
                if (!blockers(nodeAttrs).isEmpty()) {
                    findings.add(new RiskFinding("CRITICAL", "allocation", allocation.id(),
                            "allocation is on blocked node " + device.nodeHostname(),
                            "Move or release the allocation before the node is maintained."));
                }
            }
        }

        for (RuntimeWorkerRecord worker : runtimeRepository.listWorkers()) {
            if (worker.restartCount() >= worker.maxRestarts()) {
                findings.add(new RiskFinding("CRITICAL", "runtime", worker.id(),
                        "restart count reached maxRestarts=" + worker.maxRestarts(),
                        "Stop, inspect logs, checkpoint, and relaunch with corrected settings."));
            } else if (worker.restartCount() > 0) {
                findings.add(new RiskFinding("WARN", "runtime", worker.id(),
                        "worker restarted " + worker.restartCount() + " time(s)",
                        "Inspect runtime events and checkpoint health."));
            }
            if (worker.startedAt() != null && worker.maxLifetimeMinutes() > 0) {
                long ageMinutes = Duration.between(worker.startedAt(), now).toMinutes();
                if (ageMinutes > worker.maxLifetimeMinutes()) {
                    findings.add(new RiskFinding("WARN", "runtime", worker.id(),
                            "worker exceeded max lifetime",
                            "Recycle the worker during the next safe checkpoint."));
                }
            }
        }

        if (opsRepository.list("system", "safety-policy").isEmpty()) {
            findings.add(new RiskFinding("WARN", "governance", "safety-policy",
                    "no stored safety policy",
                    "Run gpum system safety policy to lock cluster limits."));
        }
        if (opsRepository.list("schedule", "queue").isEmpty()) {
            findings.add(new RiskFinding("WARN", "scheduling", "queue",
                    "no scheduling queues configured",
                    "Create queues for tenants or projects before shared production use."));
        }
        if (opsRepository.list("observe", "telemetry").isEmpty()) {
            findings.add(new RiskFinding("WARN", "observability", "telemetry",
                    "no telemetry policy configured",
                    "Create a telemetry policy for live monitoring."));
        }
        if (opsRepository.list("observe", "alert").isEmpty()) {
            findings.add(new RiskFinding("WARN", "observability", "alert",
                    "no alert policy configured",
                    "Add Slack, Teams, email, or webhook alerts for job failures and hardware risk."));
        }
        if ("1".equals(System.getenv("GPUM_ENABLE_HARDWARE_WRITE")) || Boolean.getBoolean("gpum.enableHardwareWrite")) {
            findings.add(new RiskFinding("WARN", "governance", "hardware-write",
                    "hardware mutation is enabled",
                    "Keep hardware-write enabled only inside an approved maintenance window."));
        }
        if (findings.isEmpty()) {
            findings.add(new RiskFinding("OK", "cluster", "fleet", "no risk findings", "No action required."));
        }
        findings.sort(Comparator
                .comparingInt((RiskFinding finding) -> severityRank(finding.severity())).reversed()
                .thenComparing(RiskFinding::category)
                .thenComparing(RiskFinding::scope));
        return new RiskReport(
                findings,
                findings.stream().filter(finding -> "CRITICAL".equals(finding.severity())).count(),
                findings.stream().filter(finding -> "WARN".equals(finding.severity())).count(),
                findings.stream().filter(finding -> "OK".equals(finding.severity())).count()
        );
    }

    public WorkloadValidationResult validateWorkload(WorkloadValidationRequest request) {
        initializeReadModels();
        SafetyPolicy policy = safetyPolicy();
        CapacityReport capacity = capacity();
        List<ValidationCheck> checks = new ArrayList<>();
        addCheck(checks, request.gpus() > 0, "BLOCK", "request.gpus", "GPU count must be > 0", "Set --gpus to a positive number.");
        addCheck(checks, request.hours() > 0, "BLOCK", "request.hours", "Lease hours must be > 0", "Set --hours to a positive number.");
        addCheck(checks, request.gpus() <= policy.maxGpusPerRequest(), "BLOCK", "policy.maxGpusPerRequest",
                "Requested GPUs must fit maxGpusPerRequest=" + policy.maxGpusPerRequest(),
                "Reduce --gpus or update safety policy with approval.");
        addCheck(checks, request.hours() <= policy.maxLeaseHours(), "BLOCK", "policy.maxLeaseHours",
                "Requested lease must fit maxLeaseHours=" + policy.maxLeaseHours(),
                "Reduce --hours or request approval.");
        if (request.shmSizeMb() != null) {
            addCheck(checks, request.shmSizeMb() >= 64, "BLOCK", "container.shm",
                    "Shared memory must be at least 64MiB for AI dataloaders",
                    "Raise --shm-size, commonly 16g or larger.");
            addCheck(checks, request.shmSizeMb() <= policy.maxJobShmGb() * 1024L, "BLOCK", "container.shm",
                    "Shared memory must not exceed maxJobShmGb=" + policy.maxJobShmGb(),
                    "Lower --shm-size or update safety policy.");
        }
        if (request.command() == null || request.command().isBlank()) {
            checks.add(new ValidationCheck("WARN", "job.command", "Command is empty", "Provide --command before submission."));
        }
        if (request.image() == null || request.image().isBlank()) {
            checks.add(new ValidationCheck("WARN", "job.image", "Container image is empty", "Use an immutable image tag for reproducible execution."));
        } else if (request.image().endsWith(":latest")) {
            checks.add(new ValidationCheck("WARN", "job.image", "Image tag uses latest", "Pin an explicit version or digest for reproducibility."));
        }
        if (request.cpuCores() != null) {
            boolean cpuFeasible = capacity.nodes().stream()
                    .filter(node -> "READY".equals(node.state()))
                    .anyMatch(node -> node.cpuCores() >= request.cpuCores());
            addCheck(checks, cpuFeasible, "BLOCK", "node.cpu",
                    "At least one ready node must have requested CPU cores",
                    "Lower --cpu-cores or add capacity.");
        }
        if (request.memoryMb() != null) {
            boolean memoryFeasible = capacity.nodes().stream()
                    .filter(node -> "READY".equals(node.state()))
                    .anyMatch(node -> node.memoryTotalMb() >= request.memoryMb());
            addCheck(checks, memoryFeasible, "BLOCK", "node.memory",
                    "At least one ready node must have requested RAM",
                    "Lower --memory-mb or add capacity.");
        }

        PlacementFit fit = placementFit(request);
        addCheck(checks, fit.feasible(), "BLOCK", "placement." + request.strategy(),
                fit.reason(),
                fit.recommendation());
        if (!opsRepository.list("system", "safety-policy").isEmpty()) {
            checks.add(new ValidationCheck("PASS", "policy.safety", "Safety policy is stored", "No action required."));
        } else {
            checks.add(new ValidationCheck("WARN", "policy.safety", "Safety policy is only using defaults", "Store explicit limits before production."));
        }
        boolean allowed = checks.stream().noneMatch(check -> "BLOCK".equals(check.severity()));
        boolean hasWarnings = checks.stream().anyMatch(check -> "WARN".equals(check.severity()));
        return new WorkloadValidationResult(allowed, hasWarnings, fit.selectedNode(), checks);
    }

    public ForecastReport forecast(ForecastRequest request) {
        CapacityReport capacity = capacity();
        double targetUtilization = clamp(request.targetUtilization(), 0.01, 1.0);
        double reserveRatio = clamp(request.reserveRatio(), 0.0, 0.95);
        double usableGpuHoursPerDay = capacity.totals().freeGpus() * 24.0 * targetUtilization * (1.0 - reserveRatio);
        double requestedGpuHoursPerDay = request.jobsPerDay() * request.jobGpuHours();
        double usableGpuHoursForWindow = usableGpuHoursPerDay * request.days();
        int maxJobsPerDay = request.jobGpuHours() <= 0.0 ? 0 : (int) Math.floor(usableGpuHoursPerDay / request.jobGpuHours());
        double balance = usableGpuHoursPerDay - requestedGpuHoursPerDay;
        String status;
        String recommendation;
        if (usableGpuHoursPerDay <= 0.0) {
            status = "BLOCKED";
            recommendation = "No free GPU capacity is available. Release jobs, add nodes, or reduce requested workload.";
        } else if (balance < 0.0) {
            status = "OVERCOMMITTED";
            recommendation = "Reduce jobs per day, lower GPU-hours per job, raise target capacity, or add GPUs.";
        } else if (balance < usableGpuHoursPerDay * 0.15) {
            status = "TIGHT";
            recommendation = "Capacity is close to demand. Keep backfill small and reserve emergency headroom.";
        } else {
            status = "OK";
            recommendation = "Forecast has enough free GPU-hour headroom for the requested workload.";
        }
        return new ForecastReport(
                request.days(),
                capacity.totals().freeGpus(),
                targetUtilization,
                reserveRatio,
                request.jobGpuHours(),
                request.jobsPerDay(),
                usableGpuHoursPerDay,
                requestedGpuHoursPerDay,
                usableGpuHoursForWindow,
                maxJobsPerDay,
                balance,
                status,
                recommendation
        );
    }

    private PlacementFit placementFit(WorkloadValidationRequest request) {
        List<AllocationRecord> active = allocationRepository.listActive();
        Set<String> used = allocatedGpuKeys(active);
        Map<String, Map<String, String>> attrs = inventoryRepository.listNodeAttributes();
        Map<String, List<GpuDevice>> byNode = new LinkedHashMap<>();
        for (GpuDevice gpu : inventoryRepository.listGpus()) {
            Map<String, String> nodeAttrs = attrs.getOrDefault(gpu.nodeHostname(), Map.of());
            if (!blockers(nodeAttrs).isEmpty()) {
                continue;
            }
            if (gpuQuarantined(gpu, nodeAttrs)) {
                continue;
            }
            if (used.contains(gpuKey(gpu))) {
                continue;
            }
            if (!matchesSelector(nodeAttrs, request.labelSelector())) {
                continue;
            }
            if (request.model() != null && !request.model().isBlank()
                    && (gpu.model() == null || !gpu.model().toLowerCase(Locale.ROOT).contains(request.model().toLowerCase(Locale.ROOT)))) {
                continue;
            }
            if (request.minVramMb() != null && (gpu.vramTotalMb() == null || gpu.vramTotalMb() < request.minVramMb())) {
                continue;
            }
            byNode.computeIfAbsent(gpu.nodeHostname(), ignored -> new ArrayList<>()).add(gpu);
        }
        if ("spread".equalsIgnoreCase(request.strategy())) {
            int total = byNode.values().stream().mapToInt(List::size).sum();
            Optional<String> firstNode = byNode.entrySet().stream()
                    .filter(entry -> !entry.getValue().isEmpty())
                    .map(Map.Entry::getKey)
                    .findFirst();
            return new PlacementFit(total >= request.gpus(), firstNode.orElse(null),
                    "Total matching free GPUs must be at least requested GPUs. matchingFree=" + total,
                    "Relax filters, release allocations, or add matching nodes.");
        }
        Optional<Map.Entry<String, List<GpuDevice>>> packed = byNode.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= request.gpus())
                .max(Comparator
                        .comparingInt((Map.Entry<String, List<GpuDevice>> entry) -> topologyScore(entry.getValue()))
                        .thenComparingInt(entry -> entry.getValue().size()));
        return new PlacementFit(packed.isPresent(), packed.map(Map.Entry::getKey).orElse(null),
                "A single ready node must have enough matching free GPUs for packed placement.",
                "Use --strategy spread, relax filters, or free capacity on one node.");
    }

    private List<ModelCapacity> modelCapacity(
            List<GpuDevice> gpus,
            List<AllocationRecord> active,
            Map<String, Map<String, String>> attributes
    ) {
        Set<String> used = allocatedGpuKeys(active);
        Map<String, MutableModelCapacity> byModel = new LinkedHashMap<>();
        for (GpuDevice gpu : gpus) {
            String model = safe(gpu.model());
            MutableModelCapacity row = byModel.computeIfAbsent(model, ignored -> new MutableModelCapacity(model));
            row.vendor = gpu.vendor() == null ? "-" : gpu.vendor().name();
            row.total++;
            row.totalVramMb += nullableLong(gpu.vramTotalMb());
            if (used.contains(gpuKey(gpu))) {
                row.allocated++;
            }
            Map<String, String> nodeAttrs = attributes.getOrDefault(gpu.nodeHostname(), Map.of());
            if (!blockers(nodeAttrs).isEmpty() || gpuQuarantined(gpu, nodeAttrs)) {
                row.blocked++;
            }
        }
        List<ModelCapacity> rows = new ArrayList<>();
        for (MutableModelCapacity value : byModel.values()) {
            rows.add(new ModelCapacity(
                    value.vendor,
                    value.model,
                    value.total,
                    value.allocated,
                    value.blocked,
                    Math.max(0, value.total - value.allocated - value.blocked),
                    value.totalVramMb
            ));
        }
        rows.sort(Comparator
                .comparingInt(ModelCapacity::free).reversed()
                .thenComparing(ModelCapacity::model));
        return rows;
    }

    private void initializeReadModels() {
        inventoryRepository.initialize();
        allocationRepository.initialize();
        opsRepository.initialize();
        runtimeRepository.initialize();
    }

    private SafetyPolicy safetyPolicy() {
        SafetyPolicy defaults = new SafetyPolicy(
                128,
                720,
                config.getMonitoring().getThermalWarnC(),
                config.getMonitoring().getThermalCriticalC(),
                0.02,
                2000,
                512
        );
        List<OpsRecord> policies = opsRepository.list("system", "safety-policy");
        if (policies.isEmpty()) {
            return defaults;
        }
        Map<String, String> metadata = policies.getFirst().metadata();
        return new SafetyPolicy(
                intValue(metadata, "maxGpusPerRequest", defaults.maxGpusPerRequest()),
                intValue(metadata, "maxLeaseHours", defaults.maxLeaseHours()),
                doubleValue(metadata, "thermalWarnC", defaults.thermalWarnC()),
                doubleValue(metadata, "thermalCriticalC", defaults.thermalCriticalC()),
                doubleValue(metadata, "minFreeVramRatio", defaults.minFreeVramRatio()),
                intValue(metadata, "maxPowerLimitW", defaults.maxPowerLimitW()),
                intValue(metadata, "maxJobShmGb", defaults.maxJobShmGb())
        );
    }

    private Map<String, Integer> allocatedGpuCountByNode(List<AllocationRecord> allocations) {
        Map<String, Integer> allocated = new LinkedHashMap<>();
        for (AllocationRecord allocation : allocations) {
            for (AllocationDevice device : allocation.devices()) {
                allocated.merge(device.nodeHostname(), 1, Integer::sum);
            }
        }
        return allocated;
    }

    private Set<String> allocatedGpuKeys(List<AllocationRecord> allocations) {
        return allocations.stream()
                .flatMap(allocation -> allocation.devices().stream())
                .map(this::deviceKey)
                .collect(Collectors.toSet());
    }

    private List<String> blockers(Map<String, String> attrs) {
        List<String> blockers = new ArrayList<>();
        if ("true".equalsIgnoreCase(attrs.get("state.maintenance"))) {
            blockers.add("maintenance");
        }
        if ("true".equalsIgnoreCase(attrs.get("state.drained"))) {
            blockers.add("drained");
        }
        if ("true".equalsIgnoreCase(attrs.get("state.degraded"))) {
            blockers.add("degraded");
        }
        if ("true".equalsIgnoreCase(attrs.get("state.quarantined"))) {
            blockers.add("quarantined");
        }
        return blockers;
    }

    private int countQuarantined(List<GpuDevice> gpus, Map<String, String> attrs) {
        int count = 0;
        for (GpuDevice gpu : gpus) {
            if (gpuQuarantined(gpu, attrs)) {
                count++;
            }
        }
        return count;
    }

    private boolean gpuQuarantined(GpuDevice gpu, Map<String, String> attrs) {
        return "true".equalsIgnoreCase(attrs.get(HealthScoringService.gpuQuarantineKey(gpu.deviceId())));
    }

    private boolean matchesSelector(Map<String, String> attrs, String selector) {
        if (selector == null || selector.isBlank()) {
            return true;
        }
        for (String part : selector.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String[] kv = trimmed.split("=", 2);
            if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) {
                throw new IllegalArgumentException("label-selector must be key=value[,key=value]");
            }
            String actual = attrs.get("label." + kv[0].trim());
            if (actual == null || !actual.equalsIgnoreCase(kv[1].trim())) {
                return false;
            }
        }
        return true;
    }

    private List<String> labels(Map<String, String> attrs) {
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            if (entry.getKey().startsWith("label.")) {
                labels.add(entry.getKey().substring("label.".length()) + "=" + entry.getValue());
            }
        }
        labels.sort(String::compareToIgnoreCase);
        return labels;
    }

    private String dominantModel(List<GpuDevice> gpus) {
        Map<String, Integer> counts = new HashMap<>();
        for (GpuDevice gpu : gpus) {
            counts.merge(safe(gpu.model()), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse("-");
    }

    private String topologyClass(List<GpuDevice> gpus) {
        boolean highSpeed = gpus.stream().anyMatch(gpu -> Set.of(
                InterconnectType.NVLINK,
                InterconnectType.XGMI,
                InterconnectType.XE_LINK
        ).contains(gpu.interconnectType()));
        if (highSpeed) {
            return "high-speed";
        }
        boolean pcie = gpus.stream().anyMatch(gpu -> gpu.interconnectType() == InterconnectType.PCIE);
        return pcie ? "pcie" : "unknown";
    }

    private int topologyScore(List<GpuDevice> gpus) {
        int score = 0;
        for (GpuDevice gpu : gpus) {
            if (Set.of(InterconnectType.NVLINK, InterconnectType.XGMI, InterconnectType.XE_LINK).contains(gpu.interconnectType())) {
                score += 100;
            } else if (gpu.interconnectType() == InterconnectType.PCIE) {
                score += 30;
            }
        }
        return score;
    }

    private String gpuKey(GpuDevice gpu) {
        return gpu.uuid() != null && !gpu.uuid().isBlank()
                ? "uuid:" + gpu.uuid()
                : gpu.nodeHostname() + ":" + gpu.deviceId();
    }

    private String deviceKey(AllocationDevice device) {
        return device.uuid() != null && !device.uuid().isBlank()
                ? "uuid:" + device.uuid()
                : device.nodeHostname() + ":" + device.deviceId();
    }

    private void addCheck(List<ValidationCheck> checks, boolean condition, String failSeverity, String name, String message, String action) {
        checks.add(new ValidationCheck(condition ? "PASS" : failSeverity, name, message, condition ? "No action required." : action));
    }

    private static int severityRank(String severity) {
        return switch (severity.toUpperCase(Locale.ROOT)) {
            case "CRITICAL", "BLOCK" -> 4;
            case "WARN" -> 3;
            case "PASS" -> 2;
            case "OK" -> 1;
            default -> 0;
        };
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static long nullableLong(Long value) {
        return value == null ? 0L : value;
    }

    private static long sumLong(List<GpuDevice> gpus, LongReader reader) {
        long sum = 0L;
        for (GpuDevice gpu : gpus) {
            Long value = reader.read(gpu);
            if (value != null) {
                sum += value;
            }
        }
        return sum;
    }

    private static double sumDouble(List<GpuDevice> gpus, DoubleReader reader) {
        double sum = 0.0;
        for (GpuDevice gpu : gpus) {
            Double value = reader.read(gpu);
            if (value != null) {
                sum += value;
            }
        }
        return sum;
    }

    private static double averageDouble(List<GpuDevice> gpus, DoubleReader reader) {
        double sum = 0.0;
        int count = 0;
        for (GpuDevice gpu : gpus) {
            Double value = reader.read(gpu);
            if (value != null) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static double maxDouble(List<GpuDevice> gpus, DoubleReader reader) {
        double max = 0.0;
        for (GpuDevice gpu : gpus) {
            Double value = reader.read(gpu);
            if (value != null) {
                max = Math.max(max, value);
            }
        }
        return max;
    }

    private static int intValue(Map<String, String> metadata, String key, int fallback) {
        String value = metadata.get(key);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static double doubleValue(Map<String, String> metadata, String key, double fallback) {
        String value = metadata.get(key);
        return value == null || value.isBlank() ? fallback : Double.parseDouble(value);
    }

    private interface LongReader {
        Long read(GpuDevice gpu);
    }

    private interface DoubleReader {
        Double read(GpuDevice gpu);
    }

    private static final class MutableModelCapacity {
        private final String model;
        private String vendor = "-";
        private int total;
        private int allocated;
        private int blocked;
        private long totalVramMb;

        private MutableModelCapacity(String model) {
            this.model = model;
        }
    }

    public record CapacityReport(CapacityTotals totals, List<NodeCapacity> nodes, List<ModelCapacity> models) {
    }

    public record CapacityTotals(
            int nodes,
            long readyNodes,
            int totalGpus,
            int allocatedGpus,
            int quarantinedGpus,
            int allocatableGpus,
            int freeGpus,
            long totalVramMb,
            long freeVramMb,
            double powerUsageW,
            double powerLimitW
    ) {
    }

    public record NodeCapacity(
            String node,
            String state,
            int cpuCores,
            long memoryTotalMb,
            int totalGpus,
            int allocatedGpus,
            int quarantinedGpus,
            int allocatableGpus,
            int freeGpus,
            long totalVramMb,
            long freeVramMb,
            double avgUtilization,
            double maxTemperatureC,
            double powerUsageW,
            double powerLimitW,
            String dominantModel,
            String topologyClass,
            List<String> labels,
            List<String> blockers,
            Instant lastScannedAt
    ) {
    }

    public record ModelCapacity(String vendor, String model, int total, int allocated, int blocked, int free, long totalVramMb) {
    }

    public record RiskOptions(int maxScanAgeMinutes, double minFreeVramRatio) {
        public static RiskOptions defaults() {
            return new RiskOptions(30, 0.05);
        }
    }

    public record RiskReport(List<RiskFinding> findings, long critical, long warn, long ok) {
    }

    public record RiskFinding(String severity, String category, String scope, String detail, String action) {
    }

    public record WorkloadValidationRequest(
            int gpus,
            Long minVramMb,
            int hours,
            Integer cpuCores,
            Long memoryMb,
            Long shmSizeMb,
            String model,
            String labelSelector,
            String strategy,
            String image,
            String command
    ) {
    }

    public record WorkloadValidationResult(boolean allowed, boolean hasWarnings, String selectedNode, List<ValidationCheck> checks) {
    }

    public record ValidationCheck(String severity, String name, String message, String action) {
    }

    public record ForecastRequest(int days, double targetUtilization, double reserveRatio, double jobGpuHours, int jobsPerDay) {
    }

    public record ForecastReport(
            int days,
            int freeGpus,
            double targetUtilization,
            double reserveRatio,
            double jobGpuHours,
            int jobsPerDay,
            double usableGpuHoursPerDay,
            double requestedGpuHoursPerDay,
            double usableGpuHoursForWindow,
            int maxJobsPerDay,
            double dailyBalanceGpuHours,
            String status,
            String recommendation
    ) {
    }

    private record SafetyPolicy(
            int maxGpusPerRequest,
            int maxLeaseHours,
            double thermalWarnC,
            double thermalCriticalC,
            double minFreeVramRatio,
            int maxPowerLimitW,
            int maxJobShmGb
    ) {
    }

    private record PlacementFit(boolean feasible, String selectedNode, String reason, String recommendation) {
    }
}
