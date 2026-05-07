package com.drewdrew1.core.service;

import com.drewdrew1.core.model.AllocationAffinity;
import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.AllocationRequest;
import com.drewdrew1.core.model.AllocationStatus;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuPartitionRecord;
import com.drewdrew1.core.model.QueueEntry;
import com.drewdrew1.core.model.QuotaAlertPolicy;
import com.drewdrew1.core.model.QuotaPolicy;
import com.drewdrew1.core.model.QuotaUsage;
import com.drewdrew1.core.model.UsageReportRow;
import com.drewdrew1.core.repository.AllocationRepository;
import com.drewdrew1.core.repository.GovernanceRepository;
import com.drewdrew1.core.repository.InventoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Implements queueing, quota evaluation, logical partitions, and reporting. */
public class GovernanceService {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final GovernanceRepository governanceRepository;
    private final InventoryRepository inventoryRepository;
    private final AllocationRepository allocationRepository;

    public GovernanceService(
            GovernanceRepository governanceRepository,
            InventoryRepository inventoryRepository,
            AllocationRepository allocationRepository
    ) {
        this.governanceRepository = governanceRepository;
        this.inventoryRepository = inventoryRepository;
        this.allocationRepository = allocationRepository;
    }

    public QueueEntry enqueue(AllocationRequest request, String reason) {
        governanceRepository.initialize();
        QueueEntry entry = new QueueEntry(
                "queue-" + UUID.randomUUID(),
                request.owner(),
                request.tenant(),
                request.gpuCount(),
                request.model(),
                request.minVramMb(),
                request.exclusiveNode(),
                request.hours(),
                request.priority(),
                request.preemptible(),
                request.affinity(),
                request.labelSelector(),
                "QUEUED",
                reason,
                Instant.now(),
                Instant.now()
        );
        return governanceRepository.createQueueEntry(entry);
    }

    public List<QueueEntry> listQueueEntries() {
        governanceRepository.initialize();
        List<QueueEntry> entries = new ArrayList<>(governanceRepository.listQueueEntries());
        entries.sort(Comparator.comparingInt(QueueEntry::priority).reversed().thenComparing(QueueEntry::createdAt));
        return entries;
    }

    public QueueEntry promoteQueueEntry(String id, int delta) {
        QueueEntry current = governanceRepository.findQueueEntry(id)
                .orElseThrow(() -> new IllegalArgumentException("Queue entry not found: " + id));
        int updated = Math.min(current.priority() + delta, 10);
        return governanceRepository.updateQueuePriority(id, updated);
    }

    public QueueEntry demoteQueueEntry(String id, int delta) {
        QueueEntry current = governanceRepository.findQueueEntry(id)
                .orElseThrow(() -> new IllegalArgumentException("Queue entry not found: " + id));
        int updated = Math.max(current.priority() - delta, 0);
        return governanceRepository.updateQueuePriority(id, updated);
    }

    public Optional<String> quotaViolation(AllocationRequest request) {
        governanceRepository.initialize();
        allocationRepository.initialize();
        QuotaPolicy userPolicy = governanceRepository.findQuotaPolicy(request.owner()).orElse(null);
        if (userPolicy != null) {
            String violation = quotaViolation(userPolicy, request, activeAllocationsForOwner(request.owner()));
            if (violation != null) {
                return Optional.of(violation);
            }
        }
        if (request.tenant() != null && !request.tenant().isBlank()) {
            QuotaPolicy tenantPolicy = governanceRepository.findQuotaPolicy(request.tenant()).orElse(null);
            if (tenantPolicy != null) {
                String violation = quotaViolation(tenantPolicy, request, activeAllocationsForTenant(request.tenant()));
                if (violation != null) {
                    return Optional.of(violation);
                }
            }
        }
        return Optional.empty();
    }

    public void saveQuotaPolicy(String name, Integer maxGpus, Long maxVramMb, Integer maxLeaseHours, boolean burstAllow) {
        governanceRepository.initialize();
        governanceRepository.upsertQuotaPolicy(new QuotaPolicy(name, maxGpus, maxVramMb, maxLeaseHours, burstAllow, Instant.now()));
    }

    public void saveQuotaAlerts(String name, List<Integer> thresholds) {
        governanceRepository.initialize();
        governanceRepository.upsertQuotaAlertPolicy(new QuotaAlertPolicy(name, List.copyOf(thresholds), Instant.now()));
    }

    public List<QuotaUsage> listQuotaUsage() {
        governanceRepository.initialize();
        List<QuotaUsage> usage = new ArrayList<>();
        for (QuotaPolicy policy : governanceRepository.listQuotaPolicies()) {
            usage.add(buildQuotaUsage(policy));
        }
        usage.sort(Comparator.comparing(QuotaUsage::name));
        return usage;
    }

    public Optional<QuotaUsage> quotaUsage(String name) {
        governanceRepository.initialize();
        return governanceRepository.findQuotaPolicy(name).map(this::buildQuotaUsage);
    }

    public Optional<QuotaAlertPolicy> quotaAlertPolicy(String name) {
        return governanceRepository.findQuotaAlertPolicy(name);
    }

    public List<QuotaAlertPolicy> listQuotaAlertPolicies() {
        return governanceRepository.listQuotaAlertPolicies();
    }

    public List<GpuPartitionRecord> createPartitions(String gpuSelector, String profile, int count) {
        inventoryRepository.initialize();
        GpuDevice gpu = findGpu(gpuSelector);
        if (!gpu.supportsMig() && !gpu.supportsPartitioning()) {
            throw new IllegalArgumentException("GPU does not support logical partitioning: " + gpuSelector);
        }
        List<GpuPartitionRecord> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            GpuPartitionRecord record = new GpuPartitionRecord(
                    "part-" + UUID.randomUUID(),
                    gpu.nodeHostname(),
                    gpu.deviceId(),
                    gpu.model(),
                    profile,
                    "ACTIVE",
                    Instant.now()
            );
            created.add(governanceRepository.createPartition(record));
        }
        return created;
    }

    public List<GpuPartitionRecord> listPartitions() {
        governanceRepository.initialize();
        return governanceRepository.listPartitions();
    }

    public int destroyPartitionById(String id) {
        governanceRepository.initialize();
        return governanceRepository.deletePartitionById(id);
    }

    public int destroyPartitionsByGpu(String gpuId) {
        governanceRepository.initialize();
        return governanceRepository.deletePartitionsByGpu(gpuId);
    }

    public List<String> autoOptimizeRecommendations() {
        List<String> recommendations = new ArrayList<>();
        List<QueueEntry> queue = listQueueEntries();
        Map<String, Long> demandByModel = new LinkedHashMap<>();
        for (QueueEntry entry : queue) {
            String key = entry.modelFilter() == null ? "generic" : entry.modelFilter();
            demandByModel.merge(key, (long) entry.gpuCount(), Long::sum);
        }
        for (Map.Entry<String, Long> entry : demandByModel.entrySet()) {
            recommendations.add("Queued demand for " + entry.getKey() + ": " + entry.getValue() + " GPU(s)");
        }
        if (recommendations.isEmpty()) {
            recommendations.add("No queued demand detected. Keep current partition layout.");
        }
        return recommendations;
    }

    public List<UsageReportRow> usageReport(String by) {
        allocationRepository.initialize();
        List<AllocationRecord> allocations = allocationRepository.list();
        Map<String, MutableUsage> buckets = new LinkedHashMap<>();
        for (AllocationRecord record : allocations) {
            String key = switch (by.toLowerCase(Locale.ROOT)) {
                case "tenant" -> record.tenant() == null || record.tenant().isBlank() ? "unassigned" : record.tenant();
                case "model" -> record.modelFilter() == null || record.modelFilter().isBlank() ? inferModel(record) : record.modelFilter();
                default -> record.owner();
            };
            MutableUsage bucket = buckets.computeIfAbsent(key, ignored -> new MutableUsage());
            bucket.allocationCount++;
            bucket.gpuCount += record.devices().size();
            bucket.totalLeaseHours += ChronoUnit.HOURS.between(record.createdAt(), record.expiresAt());
            bucket.totalVramMb += record.minVramMb() == null ? 0L : record.minVramMb() * Math.max(record.devices().size(), 1);
            bucket.gpuHours += (double) record.devices().size() * ChronoUnit.HOURS.between(record.createdAt(), record.expiresAt());
        }
        List<UsageReportRow> rows = new ArrayList<>();
        for (Map.Entry<String, MutableUsage> entry : buckets.entrySet()) {
            rows.add(entry.getValue().toRow(entry.getKey(), 0.0));
        }
        rows.sort(Comparator.comparing(UsageReportRow::key));
        return rows;
    }

    public List<UsageReportRow> billingReport(Path rateCardPath) {
        allocationRepository.initialize();
        Map<String, Object> raw = loadRateCard(rateCardPath);
        double defaultGpuHour = readRate(raw.get("default_gpu_hour"), 1.0);
        Map<String, Double> modelRates = new LinkedHashMap<>();
        Object models = raw.get("models");
        if (models instanceof Map<?, ?> modelMap) {
            for (Map.Entry<?, ?> entry : modelMap.entrySet()) {
                modelRates.put(String.valueOf(entry.getKey()), readRate(entry.getValue(), defaultGpuHour));
            }
        }

        Map<String, MutableUsage> buckets = new LinkedHashMap<>();
        for (AllocationRecord record : allocationRepository.list()) {
            String key = record.modelFilter() == null || record.modelFilter().isBlank() ? inferModel(record) : record.modelFilter();
            MutableUsage bucket = buckets.computeIfAbsent(key, ignored -> new MutableUsage());
            bucket.allocationCount++;
            bucket.gpuCount += record.devices().size();
            bucket.totalLeaseHours += ChronoUnit.HOURS.between(record.createdAt(), record.expiresAt());
            bucket.totalVramMb += record.minVramMb() == null ? 0L : record.minVramMb() * Math.max(record.devices().size(), 1);
            bucket.gpuHours += (double) record.devices().size() * ChronoUnit.HOURS.between(record.createdAt(), record.expiresAt());
        }

        List<UsageReportRow> rows = new ArrayList<>();
        for (Map.Entry<String, MutableUsage> entry : buckets.entrySet()) {
            double rate = findRate(modelRates, entry.getKey(), defaultGpuHour);
            rows.add(entry.getValue().toRow(entry.getKey(), rate));
        }
        rows.sort(Comparator.comparing(UsageReportRow::key));
        return rows;
    }

    private GpuDevice findGpu(String selector) {
        List<GpuDevice> matches = inventoryRepository.listGpus().stream()
                .filter(gpu -> selector.equalsIgnoreCase(gpu.deviceId())
                        || selector.equalsIgnoreCase(gpu.uuid())
                        || selector.equalsIgnoreCase(gpu.nodeHostname() + ":" + gpu.deviceId()))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("GPU not found: " + selector);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("GPU selector is ambiguous: " + selector + ". Use node:deviceId or UUID.");
        }
        return matches.getFirst();
    }

    private String quotaViolation(QuotaPolicy policy, AllocationRequest request, List<AllocationRecord> activeAllocations) {
        int activeGpuCount = activeAllocations.stream().mapToInt(record -> record.devices().size()).sum();
        long activeVramMb = activeAllocations.stream()
                .map(AllocationRecord::minVramMb)
                .filter(value -> value != null)
                .mapToLong(Long::longValue)
                .sum();
        int activeLeaseHours = activeAllocations.stream()
                .mapToInt(record -> (int) ChronoUnit.HOURS.between(record.createdAt(), record.expiresAt()))
                .sum();

        if (policy.maxGpus() != null && activeGpuCount + request.gpuCount() > policy.maxGpus()) {
            return "Quota exceeded: GPU count would exceed " + policy.maxGpus();
        }
        if (policy.maxVramMb() != null && request.minVramMb() != null
                && activeVramMb + request.minVramMb() > policy.maxVramMb()) {
            return "Quota exceeded: VRAM would exceed " + policy.maxVramMb() + " MB";
        }
        if (policy.maxLeaseHours() != null && activeLeaseHours + request.hours() > policy.maxLeaseHours()) {
            return "Quota exceeded: lease hours would exceed " + policy.maxLeaseHours();
        }
        return null;
    }

    private QuotaUsage buildQuotaUsage(QuotaPolicy policy) {
        List<AllocationRecord> allocations = activeAllocationsForOwner(policy.name());
        if (allocations.isEmpty()) {
            allocations = activeAllocationsForTenant(policy.name());
        }
        int activeGpuCount = allocations.stream().mapToInt(record -> record.devices().size()).sum();
        long activeVramMb = allocations.stream()
                .map(AllocationRecord::minVramMb)
                .filter(value -> value != null)
                .mapToLong(Long::longValue)
                .sum();
        int activeLeaseHours = allocations.stream()
                .mapToInt(record -> (int) ChronoUnit.HOURS.between(record.createdAt(), record.expiresAt()))
                .sum();
        return new QuotaUsage(
                policy.name(),
                policy.maxGpus(),
                policy.maxVramMb(),
                policy.maxLeaseHours(),
                policy.burstAllow(),
                activeGpuCount,
                activeVramMb,
                activeLeaseHours,
                policy.maxGpus() == null ? null : Math.max(policy.maxGpus() - activeGpuCount, 0),
                policy.maxVramMb() == null ? null : Math.max(policy.maxVramMb() - activeVramMb, 0),
                policy.maxLeaseHours() == null ? null : Math.max(policy.maxLeaseHours() - activeLeaseHours, 0)
        );
    }

    private List<AllocationRecord> activeAllocationsForOwner(String owner) {
        return allocationRepository.listActive().stream()
                .filter(record -> record.status() == AllocationStatus.ACTIVE)
                .filter(record -> owner.equalsIgnoreCase(record.owner()))
                .toList();
    }

    private List<AllocationRecord> activeAllocationsForTenant(String tenant) {
        return allocationRepository.listActive().stream()
                .filter(record -> record.status() == AllocationStatus.ACTIVE)
                .filter(record -> record.tenant() != null && tenant.equalsIgnoreCase(record.tenant()))
                .toList();
    }

    private String inferModel(AllocationRecord record) {
        return record.devices().isEmpty() ? "unknown" : record.devices().getFirst().model();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadRateCard(Path path) {
        try {
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("Rate card not found: " + path);
            }
            Object parsed = YAML.readValue(path.toFile(), Map.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to load rate card: " + path, e);
        }
    }

    private double readRate(Object raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(raw));
    }

    private double findRate(Map<String, Double> modelRates, String model, double fallback) {
        for (Map.Entry<String, Double> entry : modelRates.entrySet()) {
            if (model.toLowerCase(Locale.ROOT).contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return fallback;
    }

    private static final class MutableUsage {
        private int allocationCount;
        private int gpuCount;
        private long totalVramMb;
        private long totalLeaseHours;
        private double gpuHours;

        private UsageReportRow toRow(String key, double rate) {
            return new UsageReportRow(key, allocationCount, gpuCount, totalVramMb, totalLeaseHours, gpuHours, gpuHours * rate);
        }
    }
}
