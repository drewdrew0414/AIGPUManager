package com.drewdrew1.core.model;

/** Represents the main GPU interconnect topology seen by the scheduler. */
public enum InterconnectType {
    NVLINK,
    XGMI,
    XE_LINK,
    PCIE,
    UNKNOWN
}
