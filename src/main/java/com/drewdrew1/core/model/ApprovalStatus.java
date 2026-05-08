package com.drewdrew1.core.model;

/** Enumerates the lifecycle states of a privileged approval request. */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    DENIED,
    FULFILLED,
    EXPIRED,
    CANCELLED
}
