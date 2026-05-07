package com.drewdrew1.core.model;

import java.time.Instant;

/** Represents one operational log entry persisted in SQLite. */
public record LogEntry(
        long id,
        Instant createdAt,
        String level,
        String component,
        String category,
        String message,
        String context
) {
}
