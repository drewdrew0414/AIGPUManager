package com.drewdrew1.core.service;

import com.drewdrew1.core.model.ApprovalStatus;
import com.drewdrew1.core.model.RbacRole;
import com.drewdrew1.infra.persistence.SqliteGovernanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies RBAC bootstrap, approval decision, and approval consumption flows. */
class AccessControlServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void bootstrapsAdminAndConsumesApprovedRequest() {
        SqliteGovernanceRepository repository = new SqliteGovernanceRepository(tempDir.resolve("access.db"));
        AccessControlService service = new AccessControlService(repository);

        service.grantRole("alice", "alice", RbacRole.ADMIN, null);
        assertTrue(service.hasRole("alice", RbacRole.ADMIN, null));

        var pending = service.requireApprovalOrSubmit(
                "bob",
                null,
                "GPU_RESET_APPLY",
                "GPU",
                "host0:0",
                "mode=soft",
                RbacRole.APPROVER,
                null
        );
        assertNotNull(pending);
        assertEquals(ApprovalStatus.PENDING, pending.status());

        var approved = service.approve("alice", pending.id(), "reviewed");
        assertEquals(ApprovalStatus.APPROVED, approved.status());

        var secondPass = service.requireApprovalOrSubmit(
                "bob",
                null,
                "GPU_RESET_APPLY",
                "GPU",
                "host0:0",
                "mode=soft",
                RbacRole.APPROVER,
                pending.id()
        );
        assertNull(secondPass);

        var stored = repository.findApprovalRequest(pending.id()).orElseThrow();
        assertEquals(ApprovalStatus.FULFILLED, stored.status());
    }
}
