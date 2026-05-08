package com.drewdrew1.core.repository;

import com.drewdrew1.core.model.GpuPartitionRecord;
import com.drewdrew1.core.model.QueueEntry;
import com.drewdrew1.core.model.QuotaAlertPolicy;
import com.drewdrew1.core.model.QuotaPolicy;
import com.drewdrew1.core.model.ApprovalRequest;
import com.drewdrew1.core.model.RoleBinding;

import java.util.List;
import java.util.Optional;

/** Defines persistence operations for queue, quota, and logical partition state. */
public interface GovernanceRepository {
    void initialize();

    QueueEntry createQueueEntry(QueueEntry entry);

    List<QueueEntry> listQueueEntries();

    Optional<QueueEntry> findQueueEntry(String id);

    QueueEntry updateQueuePriority(String id, int newPriority);

    GpuPartitionRecord createPartition(GpuPartitionRecord partitionRecord);

    List<GpuPartitionRecord> listPartitions();

    int deletePartitionById(String id);

    int deletePartitionsByGpu(String gpuId);

    void upsertQuotaPolicy(QuotaPolicy policy);

    List<QuotaPolicy> listQuotaPolicies();

    Optional<QuotaPolicy> findQuotaPolicy(String name);

    void upsertQuotaAlertPolicy(QuotaAlertPolicy alertPolicy);

    List<QuotaAlertPolicy> listQuotaAlertPolicies();

    Optional<QuotaAlertPolicy> findQuotaAlertPolicy(String name);

    void upsertRoleBinding(RoleBinding binding);

    List<RoleBinding> listRoleBindings();

    int deleteRoleBinding(String actor, String role, String scopeType, String scopeName);

    ApprovalRequest createApprovalRequest(ApprovalRequest request);

    List<ApprovalRequest> listApprovalRequests();

    Optional<ApprovalRequest> findApprovalRequest(String id);

    void updateApprovalRequest(ApprovalRequest request);
}
