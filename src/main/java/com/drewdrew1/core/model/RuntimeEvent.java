package com.drewdrew1.core.model;

import java.time.Instant;

/** Stores one runtime worker event for audit and troubleshooting. */
public record RuntimeEvent(
        String id,
        String workerId,
        String eventType,
        String message,
        Instant createdAt
) {
}
