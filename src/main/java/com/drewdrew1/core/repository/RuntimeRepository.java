package com.drewdrew1.core.repository;

import com.drewdrew1.core.model.RuntimeEvent;
import com.drewdrew1.core.model.RuntimeWorkerRecord;

import java.util.List;
import java.util.Optional;

/** Defines persistence operations for runtime worker state and events. */
public interface RuntimeRepository {
    void initialize();

    RuntimeWorkerRecord upsertWorker(RuntimeWorkerRecord worker);

    Optional<RuntimeWorkerRecord> findWorker(String id);

    List<RuntimeWorkerRecord> listWorkers();

    List<RuntimeWorkerRecord> listWorkersByAllocation(String allocationId);

    void updateWorker(RuntimeWorkerRecord worker);

    void deleteWorker(String id);

    void appendEvent(RuntimeEvent event);

    List<RuntimeEvent> listEvents(String workerId, int limit);
}
