package com.drewdrew1.core.service;

import com.drewdrew1.core.detector.DetectionResult;
import com.drewdrew1.core.detector.GpuDetector;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.core.model.NodeInventory;
import com.drewdrew1.core.model.ScanSummary;
import com.drewdrew1.core.repository.InventoryRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Coordinates detector execution and persists normalized inventory results. */
public class InventoryService {
    private final InventoryRepository inventoryRepository;
    private final List<GpuDetector> detectors;
    private final NodeInfoProvider nodeInfoProvider;

    public InventoryService(
            InventoryRepository inventoryRepository,
            List<GpuDetector> detectors,
            NodeInfoProvider nodeInfoProvider
    ) {
        this.inventoryRepository = inventoryRepository;
        this.detectors = detectors;
        this.nodeInfoProvider = nodeInfoProvider;
    }

    public ScanSummary scanNode() {
        inventoryRepository.initialize();
        NodeInventory node = nodeInfoProvider.collectNodeInventory();
        List<GpuDevice> devices = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<GpuVendor> vendors = new LinkedHashSet<>();

        for (GpuDetector detector : detectors) {
            DetectionResult result = detector.detect(node.hostname(), node.lastScannedAt());
            devices.addAll(result.devices());
            warnings.addAll(result.warnings());
            if (!result.devices().isEmpty()) {
                vendors.add(result.vendor());
            }
        }

        inventoryRepository.saveNode(node);
        inventoryRepository.replaceNodeGpus(node.hostname(), devices);

        return new ScanSummary(node, devices.size(), vendors, warnings);
    }

    public ScanSummary scanLocalNode() {
        return scanNode();
    }
}
