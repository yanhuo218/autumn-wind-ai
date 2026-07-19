package io.github.yanhuo218.autumnwind.gateway.identity;

import java.time.Instant;
import java.util.UUID;

public record SessionIntrospectionResponse(
        Boolean active,
        UUID userId,
        String role,
        String accountStatus,
        Instant expiresAt
) {
}
