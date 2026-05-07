package com.drewdrew1.core.model;

/** Describes whether requested GPUs should be packed or spread across nodes. */
public enum AllocationAffinity {
    PACKED,
    SPREAD
}
