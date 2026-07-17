package io.github.yanhuo218.autumnwind.identity.application.session;

import io.github.yanhuo218.autumnwind.identity.domain.account.AccountStatus;
import io.github.yanhuo218.autumnwind.identity.domain.account.UserRole;

import java.time.Instant;
import java.util.UUID;

public record SessionUserView(
        UUID id,
        String email,
        String displayName,
        UserRole role,
        AccountStatus status,
        boolean emailVerified,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt
) {
}
