package com.drewdrew1.core.repository;

import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.AllocationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Defines persistence operations for allocation lifecycle data. */
public interface AllocationRepository {
    void initialize();

    AllocationRecord create(AllocationRecord record);

    List<AllocationRecord> list();

    Optional<AllocationRecord> findById(String id);

    List<AllocationRecord> listActive();

    void updateStatus(String id, AllocationStatus status, Instant releasedAt);

    void updateExpiresAt(String id, Instant expiresAt);

    int markExpiredAllocations(Instant now);
}
