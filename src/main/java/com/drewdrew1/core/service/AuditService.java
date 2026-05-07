package com.drewdrew1.core.service;

import com.drewdrew1.core.model.AuditEvent;
import com.drewdrew1.core.model.AuditQuery;
import com.drewdrew1.core.repository.AuditRepository;

import java.time.Instant;
import java.util.List;

/** Provides a thin service layer around audit event persistence. */
public class AuditService {
    private final AuditRepository auditRepository;

    public AuditService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    public void log(String eventType, String actor, String target, String details) {
        auditRepository.initialize();
        auditRepository.append(eventType, actor, target, details);
    }

    public List<AuditEvent> list() {
        auditRepository.initialize();
        return auditRepository.list();
    }

    public List<AuditEvent> traceByTarget(String target) {
        auditRepository.initialize();
        return auditRepository.traceByTarget(target);
    }

    public List<AuditEvent> listBetween(Instant from, Instant to) {
        auditRepository.initialize();
        return auditRepository.listBetween(from, to);
    }

    public List<AuditEvent> query(AuditQuery query) {
        auditRepository.initialize();
        return auditRepository.query(query);
    }
}
