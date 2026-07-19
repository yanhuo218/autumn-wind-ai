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

    @Override
    public String toString() {
        return "SessionIntrospectionResponse[active=" + active
                + ", userId=" + (userId == null ? "<NONE>" : "<REDACTED>")
                + ", role=" + role
                + ", accountStatus=" + accountStatus
                + ", expiresAt=" + expiresAt + "]";
    }
}
