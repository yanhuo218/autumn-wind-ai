package io.github.yanhuo218.autumnwind.inference.security;

import io.github.yanhuo218.autumnwind.inference.configuration.ConversationJwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationServiceJwtValidatorTest {

    private static final String ISSUER = "https://conversation.internal";
    private static final String AUDIENCE = "inference-gateway";
    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

    private final ConversationServiceJwtValidator validator = new ConversationServiceJwtValidator(
            properties(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 接受完整且未超过最大寿命的ConversationService令牌() {
        assertFalse(validator.validate(token(ISSUER, AUDIENCE, "conversation-service", "request-id", 30)).hasErrors());
    }

    @Test
    void 拒绝缺失过期或超长寿命的令牌() {
        assertTrue(validator.validate(token(ISSUER, AUDIENCE, "conversation-service", null, 30)).hasErrors());
        assertTrue(validator.validate(token(ISSUER, AUDIENCE, "conversation-service", "request-id", -300)).hasErrors());
        assertTrue(validator.validate(token(ISSUER, AUDIENCE, "conversation-service", "request-id", 61)).hasErrors());
    }

    @Test
    void 拒绝错误签发方受众或调用方() {
        assertTrue(validator.validate(token("https://other.internal", AUDIENCE, "conversation-service", "request-id", 30)).hasErrors());
        assertTrue(validator.validate(token(ISSUER, "other-service", "conversation-service", "request-id", 30)).hasErrors());
        assertTrue(validator.validate(token(ISSUER, AUDIENCE, "other-service", "request-id", 30)).hasErrors());
        assertTrue(validator.validate(token(ISSUER, AUDIENCE, null, "request-id", 30)).hasErrors());
    }

    @Test
    void 拒绝包含目标受众和额外受众的令牌() {
        assertTrue(validator.validate(token(
                ISSUER,
                List.of(AUDIENCE, "other-service"),
                "conversation-service",
                "request-id",
                30)).hasErrors());
    }

    private static ConversationJwtProperties properties() {
        return new ConversationJwtProperties(
                ISSUER,
                AUDIENCE,
                URI.create("https://conversation.internal/internal/v1/security/jwks"),
                Set.of("conversation-service"),
                Duration.ofSeconds(60));
    }

    private static Jwt token(String issuer, String audience, String subject, String jwtId, long expiresInSeconds) {
        return token(issuer, List.of(audience), subject, jwtId, expiresInSeconds);
    }

    private static Jwt token(
            String issuer,
            List<String> audiences,
            String subject,
            String jwtId,
            long expiresInSeconds
    ) {
        Instant issuedAt = expiresInSeconds < 0 ? NOW.minusSeconds(400) : NOW.minusSeconds(10);
        Jwt.Builder builder = Jwt.withTokenValue("placeholder-token")
                .header("alg", "RS256")
                .issuer(issuer)
                .audience(audiences)
                .issuedAt(issuedAt)
                .expiresAt(NOW.plusSeconds(expiresInSeconds));
        if (subject != null) {
            builder.subject(subject);
        }
        if (jwtId != null) {
            builder.claim("jti", jwtId);
        }
        return builder.build();
    }
}
