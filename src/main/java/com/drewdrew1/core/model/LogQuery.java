package com.drewdrew1.core.model;

import java.time.Instant;

/** Describes filters and sort order for persisted log queries. */
public record LogQuery(
        String level,
        String component,
        String category,
        String contains,
        Instant from,
        Instant to,
        boolean ascending,
        Integer limit
) {
}
