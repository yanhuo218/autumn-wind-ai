package io.github.yanhuo218.autumnwind.gateway.security;

import io.github.yanhuo218.autumnwind.gateway.identity.SessionIntrospectionResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveDtoToStringTest {

    private static final UUID USER_ID = UUID.fromString("4c184ec5-9127-4f43-a4b9-662d5e38846b");

    @Test
    void ServiceJWT请求toString不得暴露操作者标识() {
        String rendered = ServiceJwtRequest.actor(
                "model-registry-service", "model-registry.model.read", USER_ID).toString();

        assertTrue(rendered.contains("actorUserId=<REDACTED>"));
        assertFalse(rendered.contains(USER_ID.toString()));
    }

    @Test
    void 会话Introspection响应toString不得暴露用户标识() {
        String rendered = new SessionIntrospectionResponse(
                true, USER_ID, "USER", "ACTIVE", Instant.parse("2026-07-19T00:01:00Z")).toString();

        assertTrue(rendered.contains("userId=<REDACTED>"));
        assertFalse(rendered.contains(USER_ID.toString()));
    }
}
