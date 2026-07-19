package io.github.yanhuo218.autumnwind.gateway.security;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ServiceJwtRequest(String audience, Set<String> scopes, UUID actorUserId) {

    public ServiceJwtRequest {
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("Service JWT audience 不能为空。");
        }
        audience = audience.trim();
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("Service JWT scope 不能为空。");
        }
        LinkedHashSet<String> normalizedScopes = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope == null || scope.isBlank()) {
                throw new IllegalArgumentException("Service JWT scope 不能为空。");
            }
            normalizedScopes.add(scope.trim());
        }
        scopes = Set.copyOf(normalizedScopes);
    }

    public static ServiceJwtRequest service(String audience, String scope) {
        return new ServiceJwtRequest(audience, Set.of(scope), null);
    }

    public static ServiceJwtRequest actor(String audience, String scope, UUID actorUserId) {
        return new ServiceJwtRequest(audience, Set.of(scope), Objects.requireNonNull(actorUserId, "操作者不能为空。"));
    }

    @Override
    public String toString() {
        return "ServiceJwtRequest[audience=" + audience
                + ", scopes=" + scopes
                + ", actorUserId=" + (actorUserId == null ? "<NONE>" : "<REDACTED>") + "]";
    }
}
