package com.drewdrew1.core.model;

import java.time.Instant;

/** Assigns one role to one actor at either global or tenant scope. */
public record RoleBinding(
        String actor,
        RbacRole role,
        String scopeType,
        String scopeName,
        Instant createdAt,
        String createdBy
) {
}
