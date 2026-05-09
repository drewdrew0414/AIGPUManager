package com.drewdrew1.core.repository;

import com.drewdrew1.core.model.OpsRecord;

import java.util.List;
import java.util.Optional;

/** Defines persistence for cross-cutting operational records. */
public interface OpsRepository {
    void initialize();

    OpsRecord upsert(OpsRecord record);

    Optional<OpsRecord> find(String id);

    List<OpsRecord> list(String domain, String type);

    int updateStatus(String id, String status);

    int delete(String id);
}
