package com.drewdrew1.core.repository;

import com.drewdrew1.core.model.LogEntry;
import com.drewdrew1.core.model.LogQuery;

import java.util.List;

/** Defines persistence operations for operational log entries. */
public interface LogRepository {
    void initialize();

    void append(String level, String component, String category, String message, String context);

    List<LogEntry> query(LogQuery query);
}
