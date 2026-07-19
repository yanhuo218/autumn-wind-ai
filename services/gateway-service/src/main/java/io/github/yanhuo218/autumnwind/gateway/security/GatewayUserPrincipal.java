package io.github.yanhuo218.autumnwind.gateway.security;

import java.time.Instant;
import java.util.UUID;

public record GatewayUserPrincipal(UUID userId, String role, Instant expiresAt) {
}
