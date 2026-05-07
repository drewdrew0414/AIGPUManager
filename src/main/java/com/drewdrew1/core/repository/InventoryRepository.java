package com.drewdrew1.core.repository;

import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.NodeInventory;
import com.drewdrew1.core.model.RemoteNodeRegistration;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Defines persistence operations for node, GPU, and remote node inventory. */
public interface InventoryRepository {
    void initialize();

    void saveNode(NodeInventory nodeInventory);

    void replaceNodeGpus(String hostname, List<GpuDevice> gpus);

    List<NodeInventory> listNodes();

    List<GpuDevice> listGpus();

    Optional<NodeInventory> findNode(String hostname);

    List<GpuDevice> listGpusByNode(String hostname);

    Map<String, String> getNodeAttributes(String hostname);

    Map<String, Map<String, String>> listNodeAttributes();

    void putNodeAttribute(String hostname, String key, String value);

    void removeNodeAttribute(String hostname, String key);

    void upsertRemoteNode(RemoteNodeRegistration registration);

    List<RemoteNodeRegistration> listRemoteNodes();

    void removeRemoteNode(String address);
}
