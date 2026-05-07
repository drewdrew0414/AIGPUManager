package com.drewdrew1.core.model;

import java.time.Instant;

/** Represents a single immutable audit log event. */
public record AuditEvent(
        long id,
        Instant createdAt,
        String eventType,
        String actor,
        String target,
        String details
) {
}
