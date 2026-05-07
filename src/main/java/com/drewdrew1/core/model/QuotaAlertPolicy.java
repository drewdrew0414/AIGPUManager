package com.drewdrew1.core.model;

import java.time.Instant;
import java.util.List;

/** Represents configured quota alert thresholds for one quota target. */
public record QuotaAlertPolicy(
        String name,
        List<Integer> thresholds,
        Instant updatedAt
) {
}
