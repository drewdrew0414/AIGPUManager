package com.drewdrew1.core.model;

import java.time.Instant;

/** Stores a registered remote node target used for SSH-based scanning. */
public record RemoteNodeRegistration(
        String address,
        String sshUser,
        String alias,
        boolean enabled,
        Instant createdAt
) {
}
