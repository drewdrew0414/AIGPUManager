package com.drewdrew1.core.service;

import com.drewdrew1.core.model.AllocationAffinity;
import com.drewdrew1.core.model.AllocationDecision;
import com.drewdrew1.core.model.AllocationDevice;
import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.AllocationRequest;
import com.drewdrew1.core.model.AllocationStatus;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.repository.AllocationRepository;
import com.drewdrew1.core.repository.InventoryRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Contains scheduling and allocation lifecycle logic for GPU reservations. */
public class AllocationService {
    private final InventoryRepository inventoryRepository;
    private final AllocationRepository allocationRepository;

    public AllocationService(InventoryRepository inventoryRepository, AllocationRepository allocationRepository) {
        this.inventoryRepository = inventoryRepository;
        this.allocationRepository = allocationRepository;
    }

    public AllocationDecision dryRun(AllocationRequest request) {
        reconcileExpiredAllocations();
        return plan(request, true);
    }

    public AllocationRecord allocate(AllocationRequest request) {
        reconcileExpiredAllocations();
        return allocationRepository.create(toRecord(request, plan(request, false, null, Set.of())));
    }

    public List<AllocationRecord> listAllocations() {
        reconcileExpiredAllocations();
        return allocationRepository.list();
    }

    public Optional<AllocationRecord> findAllocation(String id) {
        reconcileExpiredAllocations();
        return allocationRepository.findById(id);
    }

    public AllocationRecord releaseAllocation(String id) {
        reconcileExpiredAllocations();
        AllocationRecord existing = allocationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Allocation not found: " + id));
        if (existing.status() != AllocationStatus.ACTIVE) {
            return existing;
        }
        Instant releasedAt = Instant.now();
        allocationRepository.updateStatus(id, AllocationStatus.RELEASED, releasedAt);
        return allocationRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Allocation disappeared after release: " + id));
    }

    public AllocationRecord extendAllocation(String id, int hours) {
        reconcileExpiredAllocations();
        AllocationRecord existing = allocationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Allocation not found: " + id));
        if (existing.status() != AllocationStatus.ACTIVE) {
            throw new IllegalArgumentException("Only active allocations can be extended.");
        }
        Instant expiresAt = existing.expiresAt().plus(hours, ChronoUnit.HOURS);
        allocationRepository.updateExpiresAt(id, expiresAt);
        return allocationRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Allocation disappeared after extend: " + id));
    }

    public void reconcileExpiredAllocations() {
        allocationRepository.initialize();
        allocationRepository.markExpiredAllocations(Instant.now());
    }

    public int reapExpiredAllocations() {
        allocationRepository.initialize();
        return allocationRepository.markExpiredAllocations(Instant.now());
    }

    public AllocationRecord moveAllocation(String id, String toNode) {
        reconcileExpiredAllocations();
        AllocationRecord existing = allocationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Allocation not found: " + id));
        if (existing.status() != AllocationStatus.ACTIVE) {
            throw new IllegalArgumentException("Only active allocations can be moved.");
        }
        if (existing.devices().stream().anyMatch(device -> toNode.equalsIgnoreCase(device.nodeHostname()))) {
            throw new IllegalArgumentException("Allocation is already placed on node: " + toNode);
        }

        AllocationRequest request = requestFromExisting(existing);
        AllocationRecord replacement = allocationRepository.create(
                toRecord(request, plan(request, false, Set.of(toNode), Set.of()))
        );
        allocationRepository.updateStatus(existing.id(), AllocationStatus.RELEASED, Instant.now());
        return replacement;
    }

    public AllocationRecord moveAllocationAwayFromNode(String id, String sourceNode) {
        reconcileExpiredAllocations();
        AllocationRecord existing = allocationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Allocation not found: " + id));
        if (existing.status() != AllocationStatus.ACTIVE) {
            throw new IllegalArgumentException("Only active allocations can be moved.");
        }
        if (existing.devices().stream().noneMatch(device -> sourceNode.equalsIgnoreCase(device.nodeHostname()))) {
            throw new IllegalArgumentException("Allocation is not placed on node: " + sourceNode);
        }

        AllocationRequest request = requestFromExisting(existing);
        AllocationRecord replacement = allocationRepository.create(
                toRecord(request, plan(request, false, null, Set.of(sourceNode)))
        );
        allocationRepository.updateStatus(existing.id(), AllocationStatus.RELEASED, Instant.now());
        return replacement;
    }

    private AllocationDecision plan(AllocationRequest request, boolean dryRun) {
        return plan(request, dryRun, null, Set.of());
    }

    private AllocationDecision plan(
            AllocationRequest request,
            boolean dryRun,
            Set<String> allowedNodes,
            Set<String> excludedNodes
    ) {
        inventoryRepository.initialize();
        allocationRepository.initialize();

        Map<String, Map<String, String>> nodeAttributes = inventoryRepository.listNodeAttributes();
        List<GpuDevice> inventory = inventoryRepository.listGpus();
        List<AllocationRecord> activeAllocations = allocationRepository.listActive();
        Set<String> allocatedGpuKeys = allocatedGpuKeys(activeAllocations);
        Set<String> blockedNodes = blockedNodes(activeAllocations);

        Map<String, List<GpuDevice>> eligibleByNode = new LinkedHashMap<>();
        for (GpuDevice gpu : inventory) {
            if (allowedNodes != null && !allowedNodes.contains(gpu.nodeHostname())) {
                continue;
            }
            if (excludedNodes.contains(gpu.nodeHostname())) {
                continue;
            }
            if (blockedNodes.contains(gpu.nodeHostname())) {
                continue;
            }
            if (!nodeSchedulable(nodeAttributes.getOrDefault(gpu.nodeHostname(), Map.of()))) {
                continue;
            }
            if (!matchesSelector(nodeAttributes.getOrDefault(gpu.nodeHostname(), Map.of()), request.labelSelector())) {
                continue;
            }
            if (!matchesRequest(gpu, request)) {
                continue;
            }
            if (allocatedGpuKeys.contains(gpuKey(gpu))) {
                continue;
            }
            eligibleByNode.computeIfAbsent(gpu.nodeHostname(), ignored -> new ArrayList<>()).add(gpu);
        }

        if (request.exclusiveNode()) {
            eligibleByNode.entrySet().removeIf(entry -> hasAnyActiveAllocationOnNode(activeAllocations, entry.getKey()));
        }

        List<AllocationDevice> chosen = switch (request.affinity()) {
            case SPREAD -> chooseSpread(eligibleByNode, request.gpuCount());
            case PACKED -> choosePacked(eligibleByNode, request.gpuCount());
        };

        if (chosen.size() != request.gpuCount()) {
            throw new IllegalStateException("No allocation candidate satisfies the request.");
        }

        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plus(request.hours(), ChronoUnit.HOURS);
        String primaryNode = chosen.isEmpty() ? null : chosen.get(0).nodeHostname();

        return new AllocationDecision(request, chosen, primaryNode, createdAt, expiresAt, dryRun);
    }

    private AllocationRecord toRecord(AllocationRequest request, AllocationDecision decision) {
        return new AllocationRecord(
                "alloc-" + UUID.randomUUID(),
                request.owner(),
                request.tenant(),
                AllocationStatus.ACTIVE,
                request.exclusiveNode(),
                request.priority(),
                request.preemptible(),
                request.affinity(),
                request.gpuCount(),
                request.model(),
                request.minVramMb(),
                request.labelSelector(),
                decision.primaryNodeHostname(),
                decision.createdAt(),
                decision.expiresAt(),
                null,
                decision.devices()
        );
    }

    private AllocationRequest requestFromExisting(AllocationRecord record) {
        return new AllocationRequest(
                record.owner(),
                record.tenant(),
                record.requestedGpuCount(),
                record.modelFilter(),
                record.minVramMb(),
                record.exclusiveNode(),
                remainingHours(record.expiresAt()),
                record.priority(),
                record.preemptible(),
                record.affinity(),
                record.labelSelector()
        );
    }

    private int remainingHours(Instant expiresAt) {
        long seconds = Math.max(0L, Duration.between(Instant.now(), expiresAt).getSeconds());
        return Math.max(1, (int) Math.ceil(seconds / 3600.0));
    }

    private boolean matchesRequest(GpuDevice gpu, AllocationRequest request) {
        if (request.model() != null && (gpu.model() == null ||
                !gpu.model().toLowerCase(Locale.ROOT).contains(request.model().toLowerCase(Locale.ROOT)))) {
            return false;
        }
        if (request.minVramMb() != null && (gpu.vramTotalMb() == null || gpu.vramTotalMb() < request.minVramMb())) {
            return false;
        }
        return true;
    }

    private boolean nodeSchedulable(Map<String, String> attrs) {
        return !"true".equalsIgnoreCase(attrs.get("state.maintenance"))
                && !"true".equalsIgnoreCase(attrs.get("state.drained"));
    }

    private boolean matchesSelector(Map<String, String> attrs, String selector) {
        if (selector == null || selector.isBlank()) {
            return true;
        }
        Map<String, String> required = parseSelector(selector);
        for (Map.Entry<String, String> entry : required.entrySet()) {
            String actual = attrs.get("label." + entry.getKey());
            if (actual == null || !actual.equalsIgnoreCase(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private Map<String, String> parseSelector(String selector) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String part : selector.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String[] kv = trimmed.split("=", 2);
            if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) {
                throw new IllegalArgumentException("label-selector must be key=value[,key=value]");
            }
            parsed.put(kv[0].trim(), kv[1].trim());
        }
        return parsed;
    }

    private List<AllocationDevice> choosePacked(Map<String, List<GpuDevice>> byNode, int gpuCount) {
        List<Map.Entry<String, List<GpuDevice>>> candidates = new ArrayList<>(byNode.entrySet());
        candidates.sort(Comparator
                .comparingInt((Map.Entry<String, List<GpuDevice>> entry) -> entry.getValue().size())
                .thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, List<GpuDevice>> entry : candidates) {
            if (entry.getValue().size() >= gpuCount) {
                return toDevices(entry.getValue().subList(0, gpuCount));
            }
        }
        return List.of();
    }

    private List<AllocationDevice> chooseSpread(Map<String, List<GpuDevice>> byNode, int gpuCount) {
        Map<String, ArrayDeque<GpuDevice>> queues = new LinkedHashMap<>();
        List<String> nodes = new ArrayList<>(byNode.keySet());
        nodes.sort(Comparator.comparing((String node) -> byNode.get(node).size()).reversed().thenComparing(node -> node));
        for (String node : nodes) {
            queues.put(node, new ArrayDeque<>(byNode.get(node)));
        }

        List<GpuDevice> selected = new ArrayList<>();
        boolean madeProgress;
        do {
            madeProgress = false;
            for (String node : nodes) {
                ArrayDeque<GpuDevice> queue = queues.get(node);
                if (queue != null && !queue.isEmpty() && selected.size() < gpuCount) {
                    selected.add(queue.removeFirst());
                    madeProgress = true;
                }
            }
        } while (madeProgress && selected.size() < gpuCount);

        return toDevices(selected);
    }

    private List<AllocationDevice> toDevices(Collection<GpuDevice> gpus) {
        List<AllocationDevice> devices = new ArrayList<>();
        for (GpuDevice gpu : gpus) {
            devices.add(new AllocationDevice(
                    gpu.nodeHostname(),
                    gpu.vendor(),
                    gpu.deviceId(),
                    gpu.uuid(),
                    gpu.model(),
                    gpu.pciBusId()
            ));
        }
        return devices;
    }

    private Set<String> allocatedGpuKeys(List<AllocationRecord> allocations) {
        Set<String> keys = new HashSet<>();
        for (AllocationRecord allocation : allocations) {
            for (AllocationDevice device : allocation.devices()) {
                keys.add(deviceKey(device));
            }
        }
        return keys;
    }

    private Set<String> blockedNodes(List<AllocationRecord> allocations) {
        Set<String> nodes = new HashSet<>();
        for (AllocationRecord allocation : allocations) {
            if (allocation.exclusiveNode()) {
                for (AllocationDevice device : allocation.devices()) {
                    nodes.add(device.nodeHostname());
                }
            }
        }
        return nodes;
    }

    private boolean hasAnyActiveAllocationOnNode(List<AllocationRecord> allocations, String node) {
        for (AllocationRecord allocation : allocations) {
            for (AllocationDevice device : allocation.devices()) {
                if (node.equalsIgnoreCase(device.nodeHostname())) {
                    return true;
                }
            }
        }
        return false;
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
}
