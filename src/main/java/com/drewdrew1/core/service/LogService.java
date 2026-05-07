package com.drewdrew1.core.service;

import com.drewdrew1.core.model.LogEntry;
import com.drewdrew1.core.model.LogQuery;
import com.drewdrew1.core.repository.LogRepository;

import java.util.List;

/** Persists and retrieves operational logs for gpum workflows. */
public class LogService {
    private final LogRepository logRepository;

    public LogService(LogRepository logRepository) {
        this.logRepository = logRepository;
    }

    public void log(String level, String component, String category, String message, String context) {
        logRepository.initialize();
        logRepository.append(level, component, category, message, context);
    }

    public void info(String component, String category, String message, String context) {
        log("INFO", component, category, message, context);
    }

    public void warn(String component, String category, String message, String context) {
        log("WARN", component, category, message, context);
    }

    public void error(String component, String category, String message, String context) {
        log("ERROR", component, category, message, context);
    }

    public List<LogEntry> query(LogQuery query) {
        logRepository.initialize();
        return logRepository.query(query);
    }
}
