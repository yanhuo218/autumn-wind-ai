package io.github.yanhuo218.autumnwind.identity.application.session;

import io.github.yanhuo218.autumnwind.identity.domain.account.AccountStatus;
import io.github.yanhuo218.autumnwind.identity.domain.account.UserRole;

import java.time.Instant;
import java.util.UUID;

public record SessionIntrospection(
        boolean active,
        UUID userId,
        UserRole role,
        AccountStatus accountStatus,
        Instant expiresAt
) {

    public static SessionIntrospection inactive() {
        return new SessionIntrospection(false, null, null, null, null);
    }
}
