package com.drewdrew1.core.repository;

import com.drewdrew1.core.model.AuditEvent;
import com.drewdrew1.core.model.AuditQuery;

import java.time.Instant;
import java.util.List;

/** Defines persistence operations for immutable audit events. */
public interface AuditRepository {
    void initialize();

    void append(String eventType, String actor, String target, String details);

    List<AuditEvent> list();

    List<AuditEvent> traceByTarget(String target);

    List<AuditEvent> listBetween(Instant from, Instant to);

    List<AuditEvent> query(AuditQuery query);
}
