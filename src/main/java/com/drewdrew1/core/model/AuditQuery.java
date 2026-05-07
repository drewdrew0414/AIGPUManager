package com.drewdrew1.core.model;

import java.time.Instant;

/** Describes filters and sort order for immutable audit event queries. */
public record AuditQuery(
        String eventType,
        String actor,
        String target,
        String contains,
        Instant from,
        Instant to,
        boolean ascending,
        Integer limit
) {
}
