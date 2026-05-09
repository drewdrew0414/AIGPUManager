package com.drewdrew1.core.service;

import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.InterconnectType;
import com.drewdrew1.core.repository.AllocationRepository;
import com.drewdrew1.core.repository.InventoryRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Implements bin-packing, topology-aware placement, and backfill planning. */
public class SchedulingEngineService {
    private final InventoryRepository inventoryRepository;
    private final AllocationRepository allocationRepository;

    public SchedulingEngineService(InventoryRepository inventoryRepository, AllocationRepository allocationRepository) {
        this.inventoryRepository = inventoryRepository;
        this.allocationRepository = allocationRepository;
    }

    public PlacementPlan place(int gpuCount, Long minVramMb, String model, String strategy) {
        inventoryRepository.initialize();
        allocationRepository.initialize();
        Map<String, Integer> usedByNode = usedGpuCountByNode();
        Map<String, List<GpuDevice>> candidates = new LinkedHashMap<>();
        for (GpuDevice gpu : inventoryRepository.listGpus()) {
            if (minVramMb != null && (gpu.vramTotalMb() == null || gpu.vramTotalMb() < minVramMb)) {
                continue;
            }
            if (model != null && (gpu.model() == null || !gpu.model().toLowerCase(Locale.ROOT).contains(model.toLowerCase(Locale.ROOT)))) {
                continue;
            }
            candidates.computeIfAbsent(gpu.nodeHostname(), ignored -> new ArrayList<>()).add(gpu);
        }
        List<NodeScore> scores = new ArrayList<>();
        for (Map.Entry<String, List<GpuDevice>> entry : candidates.entrySet()) {
            int total = entry.getValue().size();
            int used = usedByNode.getOrDefault(entry.getKey(), 0);
            int free = Math.max(0, total - used);
            if (free < gpuCount) {
                continue;
            }
            int topology = topologyScore(entry.getValue());
            int remainingAfter = free - gpuCount;
            double fragmentation = total == 0 ? 1.0 : (double) remainingAfter / total;
            scores.add(new NodeScore(entry.getKey(), total, used, free, topology, fragmentation));
        }
        Comparator<NodeScore> comparator = switch (strategy.toLowerCase(Locale.ROOT)) {
            case "worst-fit" -> Comparator.comparingInt(NodeScore::freeGpus).reversed();
            case "topology" -> Comparator.comparingInt(NodeScore::topologyScore).reversed()
                    .thenComparing(Comparator.comparingInt(NodeScore::freeGpus));
            default -> Comparator.comparingInt(NodeScore::freeGpus)
                    .thenComparing(Comparator.comparingInt(NodeScore::topologyScore).reversed());
        };
        scores.sort(comparator.thenComparing(NodeScore::node));
        boolean feasible = !scores.isEmpty();
        NodeScore selected = feasible ? scores.get(0) : null;
        return new PlacementPlan(
                strategy,
                feasible,
                selected == null ? null : selected.node(),
                selected == null ? "no node has enough matching free GPUs" : "selected by " + strategy,
                scores
        );
    }

    public BackfillPlan backfill(int maxMinutes, int maxGpus, String queue) {
        Instant now = Instant.now();
        List<AllocationRecord> active = allocationRepository.listActive();
        int freeGpus = Math.max(0, inventoryRepository.listGpus().size() - active.stream().mapToInt(record -> record.devices().size()).sum());
        Instant nextExpiry = active.stream()
                .map(AllocationRecord::expiresAt)
                .filter(expiry -> expiry.isAfter(now))
                .min(Instant::compareTo)
                .orElse(now.plus(Duration.ofMinutes(maxMinutes)));
        long idleMinutes = Math.min(maxMinutes, Math.max(0L, Duration.between(now, nextExpiry).toMinutes()));
        boolean feasible = freeGpus > 0 && freeGpus <= maxGpus && idleMinutes > 0;
        return new BackfillPlan(queue, feasible, freeGpus, idleMinutes,
                feasible ? "short jobs can run until next allocation expiry" : "no safe backfill window found");
    }

    private Map<String, Integer> usedGpuCountByNode() {
        Map<String, Integer> used = new LinkedHashMap<>();
        for (AllocationRecord record : allocationRepository.listActive()) {
            record.devices().forEach(device -> used.merge(device.nodeHostname(), 1, Integer::sum));
        }
        return used;
    }

    private int topologyScore(List<GpuDevice> gpus) {
        int score = 0;
        for (GpuDevice gpu : gpus) {
            InterconnectType type = gpu.interconnectType();
            if (Set.of(InterconnectType.NVLINK, InterconnectType.XGMI, InterconnectType.XE_LINK).contains(type)) {
                score += 100;
            } else if (type == InterconnectType.PCIE) {
                score += 30;
            }
        }
        return score;
    }

    public record NodeScore(String node, int totalGpus, int usedGpus, int freeGpus, int topologyScore, double fragmentationRatio) {
    }

    public record PlacementPlan(String strategy, boolean feasible, String selectedNode, String reason, List<NodeScore> candidates) {
    }

    public record BackfillPlan(String queue, boolean feasible, int freeGpus, long idleMinutes, String reason) {
    }
}
