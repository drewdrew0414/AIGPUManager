package com.drewdrew1.core.service;

import com.drewdrew1.core.model.ApprovalRequest;
import com.drewdrew1.core.model.ApprovalStatus;
import com.drewdrew1.core.model.RbacRole;
import com.drewdrew1.core.model.RoleBinding;
import com.drewdrew1.core.repository.GovernanceRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Evaluates actor permissions and manages approval requests for risky operations. */
public class AccessControlService {
    private static final String GLOBAL_SCOPE = "GLOBAL";
    private static final String TENANT_SCOPE = "TENANT";

    private final GovernanceRepository governanceRepository;

    public AccessControlService(GovernanceRepository governanceRepository) {
        this.governanceRepository = governanceRepository;
    }

    public void grantRole(String grantedBy, String actor, RbacRole role, String tenant) {
        governanceRepository.initialize();
        requireRoleManagement(grantedBy, actor, role);
        governanceRepository.upsertRoleBinding(new RoleBinding(
                normalizeActor(actor),
                role,
                tenant == null ? GLOBAL_SCOPE : TENANT_SCOPE,
                normalizeTenant(tenant),
                Instant.now(),
                normalizeActor(grantedBy)
        ));
    }

    public int revokeRole(String revokedBy, String actor, RbacRole role, String tenant) {
        governanceRepository.initialize();
        requireRoleManagement(revokedBy, actor, role);
        return governanceRepository.deleteRoleBinding(
                normalizeActor(actor),
                role.name(),
                tenant == null ? GLOBAL_SCOPE : TENANT_SCOPE,
                normalizeTenant(tenant)
        );
    }

    public List<RoleBinding> listRoleBindings() {
        governanceRepository.initialize();
        List<RoleBinding> bindings = new ArrayList<>(governanceRepository.listRoleBindings());
        bindings.sort(Comparator
                .comparing(RoleBinding::actor)
                .thenComparing(binding -> binding.role().name())
                .thenComparing(RoleBinding::scopeType)
                .thenComparing(binding -> binding.scopeName() == null ? "" : binding.scopeName()));
        return bindings;
    }

    public boolean hasRole(String actor, RbacRole role, String tenant) {
        governanceRepository.initialize();
        String normalizedActor = normalizeActor(actor);
        String normalizedTenant = normalizeTenant(tenant);
        List<RoleBinding> bindings = governanceRepository.listRoleBindings();
        for (RoleBinding binding : bindings) {
            if (!normalizeActor(binding.actor()).equals(normalizedActor)) {
                continue;
            }
            if (binding.role() == RbacRole.ADMIN && GLOBAL_SCOPE.equals(binding.scopeType())) {
                return true;
            }
            if (binding.role() != role) {
                continue;
            }
            if (GLOBAL_SCOPE.equals(binding.scopeType())) {
                return true;
            }
            if (tenant != null && TENANT_SCOPE.equals(binding.scopeType())
                    && normalizeTenant(binding.scopeName()).equals(normalizedTenant)) {
                return true;
            }
        }
        return false;
    }

    public void requireRole(String actor, RbacRole role, String tenant, String message) {
        if (!hasRole(actor, role, tenant)) {
            throw new IllegalArgumentException(message);
        }
    }

    public ApprovalRequest requireApprovalOrSubmit(
            String requester,
            String tenant,
            String action,
            String resourceType,
            String resourceId,
            String summary,
            RbacRole requiredRole,
            String approvalId
    ) {
        governanceRepository.initialize();
        expireStaleApprovals();
        String normalizedRequester = normalizeActor(requester);
        if (hasRole(normalizedRequester, RbacRole.ADMIN, tenant) || hasRole(normalizedRequester, requiredRole, tenant)) {
            return null;
        }
        if (approvalId == null || approvalId.isBlank()) {
            return governanceRepository.createApprovalRequest(new ApprovalRequest(
                    "approval-" + UUID.randomUUID(),
                    normalizedRequester,
                    normalizeTenant(tenant),
                    action,
                    resourceType,
                    resourceId,
                    summary,
                    requiredRole,
                    ApprovalStatus.PENDING,
                    null,
                    null,
                    Instant.now(),
                    null,
                    Instant.now().plus(24, ChronoUnit.HOURS)
            ));
        }
        ApprovalRequest request = governanceRepository.findApprovalRequest(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("Approval request not found: " + approvalId));
        validateApprovedRequest(request, normalizedRequester, tenant, action, resourceType, resourceId, requiredRole);
        ApprovalRequest fulfilled = new ApprovalRequest(
                request.id(),
                request.requester(),
                request.tenant(),
                request.action(),
                request.resourceType(),
                request.resourceId(),
                request.summary(),
                request.requiredRole(),
                ApprovalStatus.FULFILLED,
                request.approvedBy(),
                request.decisionReason(),
                request.createdAt(),
                Instant.now(),
                request.expiresAt()
        );
        governanceRepository.updateApprovalRequest(fulfilled);
        return null;
    }

    public List<ApprovalRequest> listApprovalRequests() {
        governanceRepository.initialize();
        expireStaleApprovals();
        return governanceRepository.listApprovalRequests();
    }

    public ApprovalRequest approve(String approver, String id, String reason) {
        governanceRepository.initialize();
        expireStaleApprovals();
        ApprovalRequest request = governanceRepository.findApprovalRequest(id)
                .orElseThrow(() -> new IllegalArgumentException("Approval request not found: " + id));
        requireCanDecide(normalizeActor(approver), request);
        if (request.status() != ApprovalStatus.PENDING) {
            throw new IllegalArgumentException("Only pending requests can be approved.");
        }
        ApprovalRequest approved = new ApprovalRequest(
                request.id(),
                request.requester(),
                request.tenant(),
                request.action(),
                request.resourceType(),
                request.resourceId(),
                request.summary(),
                request.requiredRole(),
                ApprovalStatus.APPROVED,
                normalizeActor(approver),
                normalizeReason(reason),
                request.createdAt(),
                Instant.now(),
                request.expiresAt()
        );
        governanceRepository.updateApprovalRequest(approved);
        return approved;
    }

    public ApprovalRequest deny(String approver, String id, String reason) {
        governanceRepository.initialize();
        expireStaleApprovals();
        ApprovalRequest request = governanceRepository.findApprovalRequest(id)
                .orElseThrow(() -> new IllegalArgumentException("Approval request not found: " + id));
        requireCanDecide(normalizeActor(approver), request);
        if (request.status() != ApprovalStatus.PENDING) {
            throw new IllegalArgumentException("Only pending requests can be denied.");
        }
        ApprovalRequest denied = new ApprovalRequest(
                request.id(),
                request.requester(),
                request.tenant(),
                request.action(),
                request.resourceType(),
                request.resourceId(),
                request.summary(),
                request.requiredRole(),
                ApprovalStatus.DENIED,
                normalizeActor(approver),
                normalizeReason(reason),
                request.createdAt(),
                Instant.now(),
                request.expiresAt()
        );
        governanceRepository.updateApprovalRequest(denied);
        return denied;
    }

    public boolean hasAnyRoleBindings() {
        governanceRepository.initialize();
        return !governanceRepository.listRoleBindings().isEmpty();
    }

    private void requireRoleManagement(String actor, String targetActor, RbacRole targetRole) {
        String normalizedActor = normalizeActor(actor);
        if (!hasAnyRoleBindings()) {
            return;
        }
        if (!hasRole(normalizedActor, RbacRole.ADMIN, null)) {
            throw new IllegalArgumentException("Global ADMIN role is required to manage RBAC bindings.");
        }
        if (targetRole == RbacRole.ADMIN && !normalizedActor.equals(normalizeActor(targetActor))) {
            // Allow admins to grant admin to others, but keep the rule explicit.
            return;
        }
    }

    private void requireCanDecide(String actor, ApprovalRequest request) {
        if (hasRole(actor, RbacRole.ADMIN, request.tenant()) || hasRole(actor, RbacRole.APPROVER, request.tenant())) {
            return;
        }
        throw new IllegalArgumentException("APPROVER or ADMIN role is required to decide approval requests.");
    }

    private void validateApprovedRequest(
            ApprovalRequest request,
            String requester,
            String tenant,
            String action,
            String resourceType,
            String resourceId,
            RbacRole requiredRole
    ) {
        if (request.status() != ApprovalStatus.APPROVED) {
            throw new IllegalArgumentException("Approval request is not approved: " + request.id());
        }
        if (request.expiresAt().isBefore(Instant.now())) {
            ApprovalRequest expired = new ApprovalRequest(
                    request.id(),
                    request.requester(),
                    request.tenant(),
                    request.action(),
                    request.resourceType(),
                    request.resourceId(),
                    request.summary(),
                    request.requiredRole(),
                    ApprovalStatus.EXPIRED,
                    request.approvedBy(),
                    request.decisionReason(),
                    request.createdAt(),
                    request.decidedAt(),
                    request.expiresAt()
            );
            governanceRepository.updateApprovalRequest(expired);
            throw new IllegalArgumentException("Approval request has expired: " + request.id());
        }
        if (!normalizeActor(request.requester()).equals(requester)) {
            throw new IllegalArgumentException("Approval request requester mismatch.");
        }
        if (!safeEquals(normalizeTenant(request.tenant()), normalizeTenant(tenant))) {
            throw new IllegalArgumentException("Approval request tenant mismatch.");
        }
        if (!request.action().equalsIgnoreCase(action)
                || !request.resourceType().equalsIgnoreCase(resourceType)
                || !request.resourceId().equalsIgnoreCase(resourceId)
                || request.requiredRole() != requiredRole) {
            throw new IllegalArgumentException("Approval request does not match this operation.");
        }
    }

    private void expireStaleApprovals() {
        Instant now = Instant.now();
        for (ApprovalRequest request : governanceRepository.listApprovalRequests()) {
            if (request.status() == ApprovalStatus.PENDING || request.status() == ApprovalStatus.APPROVED) {
                if (request.expiresAt().isBefore(now)) {
                    governanceRepository.updateApprovalRequest(new ApprovalRequest(
                            request.id(),
                            request.requester(),
                            request.tenant(),
                            request.action(),
                            request.resourceType(),
                            request.resourceId(),
                            request.summary(),
                            request.requiredRole(),
                            ApprovalStatus.EXPIRED,
                            request.approvedBy(),
                            request.decisionReason(),
                            request.createdAt(),
                            request.decidedAt(),
                            request.expiresAt()
                    ));
                }
            }
        }
    }

    private String normalizeActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor must not be blank");
        }
        return actor.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeTenant(String tenant) {
        return tenant == null || tenant.isBlank() ? null : tenant.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason.trim();
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
