package io.github.yanhuo218.autumnwind.identity.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthSessionEntityTest {

    @Test
    void 会话过期或撤销后不可用() {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        AuthSessionEntity session = new AuthSessionEntity(
                UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64),
                now.plusSeconds(3600), now
        );

        assertTrue(session.isActive(now));
        assertFalse(session.isActive(now.plusSeconds(3600)));

        session.revoke(now.plusSeconds(1));
        assertFalse(session.isActive(now.plusSeconds(2)));
    }
}
