package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.yanhuo218.autumnwind.identity.application.session.SessionIntrospection;
import io.github.yanhuo218.autumnwind.identity.domain.account.AccountStatus;
import io.github.yanhuo218.autumnwind.identity.domain.account.UserRole;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionIntrospectionView(
        boolean active,
        UUID userId,
        UserRole role,
        AccountStatus accountStatus,
        Instant expiresAt
) {

    public static SessionIntrospectionView from(SessionIntrospection introspection) {
        return new SessionIntrospectionView(
                introspection.active(),
                introspection.userId(),
                introspection.role(),
                introspection.accountStatus(),
                introspection.expiresAt()
        );
    }
}
