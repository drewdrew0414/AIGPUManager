package com.drewdrew1.core.repository;

import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.NodeInventory;

import java.util.List;

public interface InventoryRepository {
    void initialize();

    void saveNode(NodeInventory nodeInventory);

    void replaceNodeGpus(String hostname, List<GpuDevice> gpus);

    List<NodeInventory> listNodes();

    List<GpuDevice> listGpus();
}
