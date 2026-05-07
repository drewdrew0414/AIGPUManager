package com.drewdrew1.core.service;

import com.drewdrew1.core.model.NodeInventory;

/** Supplies node-level system facts for local or remote scanning flows. */
public interface NodeInfoProvider {
    NodeInventory collectNodeInventory();
}
