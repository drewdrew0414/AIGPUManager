package com.drewdrew1.core.model;

import java.time.Instant;
import java.util.Map;

/** Stores operational policies, jobs, reservations, datasets, alerts, and developer artifacts. */
public record OpsRecord(
        String id,
        String domain,
        String type,
        String name,
        String owner,
        String target,
        String status,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt
) {
}
