package com.drewdrew1.core.model;

import java.time.Instant;

/** Stores one approval request for a high-risk or privileged operation. */
public record ApprovalRequest(
        String id,
        String requester,
        String tenant,
        String action,
        String resourceType,
        String resourceId,
        String summary,
        RbacRole requiredRole,
        ApprovalStatus status,
        String approvedBy,
        String decisionReason,
        Instant createdAt,
        Instant decidedAt,
        Instant expiresAt
) {
}
