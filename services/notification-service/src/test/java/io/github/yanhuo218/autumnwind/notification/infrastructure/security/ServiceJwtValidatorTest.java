package io.github.yanhuo218.autumnwind.notification.infrastructure.security;

import io.github.yanhuo218.autumnwind.notification.infrastructure.configuration.ServiceJwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceJwtValidatorTest {

    private static final String ISSUER = "https://issuer.example";
    private static final String AUDIENCE = "notification-service";
    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    private final ServiceJwtValidator validator = new ServiceJwtValidator(
            properties(),
            Clock.fixed(NOW, java.time.ZoneOffset.UTC)
    );

    @Test
    void 接受时间签发方受众和调用方均有效的Token() {
        assertFalse(validator.validate(token(ISSUER, AUDIENCE, "gateway-service", 240)).hasErrors());
    }

    @Test
    void 拒绝错误签发方受众或调用方() {
        assertTrue(validator.validate(token("https://other.example", AUDIENCE, "gateway-service", 240)).hasErrors());
        assertTrue(validator.validate(token(ISSUER, "other-service", "gateway-service", 240)).hasErrors());
        assertTrue(validator.validate(token(ISSUER, AUDIENCE, "unknown-service", 240)).hasErrors());
    }

    @Test
    void 拒绝缺少或类型错误的调用方() {
        Jwt missingSubject = token(ISSUER, AUDIENCE, null, 240);
        Jwt invalidSubjectType = tokenBuilder(ISSUER, AUDIENCE, 240)
                .claim("sub", 42)
                .build();

        assertTrue(validator.validate(missingSubject).hasErrors());
        assertTrue(validator.validate(invalidSubjectType).hasErrors());
    }

    @Test
    void 拒绝过期超长或未来签发Token() {
        assertTrue(validator.validate(token(ISSUER, AUDIENCE, "gateway-service", -300)).hasErrors());
        assertTrue(validator.validate(token(ISSUER, AUDIENCE, "gateway-service", 3600)).hasErrors());
        Jwt futureToken = tokenBuilder(ISSUER, AUDIENCE, 240)
                .issuedAt(NOW.plusSeconds(61))
                .expiresAt(NOW.plusSeconds(241))
                .subject("gateway-service")
                .build();
        assertTrue(validator.validate(futureToken).hasErrors());
    }

    @Test
    void 所有时间校验使用注入时钟而不是系统时钟() {
        Instant controlledNow = Instant.parse("2020-01-01T00:00:00Z");
        ServiceJwtValidator controlledValidator = new ServiceJwtValidator(
                properties(),
                Clock.fixed(controlledNow, java.time.ZoneOffset.UTC)
        );
        Jwt controlledToken = Jwt.withTokenValue("test-service-token")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issuedAt(controlledNow.minusSeconds(30))
                .expiresAt(controlledNow.plusSeconds(240))
                .subject("gateway-service")
                .build();

        assertFalse(controlledValidator.validate(controlledToken).hasErrors());
    }

    @Test
    void 配置拒绝非HttpsJwk地址和空调用方() {
        assertThrows(IllegalArgumentException.class, () -> new ServiceJwtProperties(
                ISSUER,
                AUDIENCE,
                URI.create("http://issuer.example/jwks"),
                Set.of("gateway-service"),
                Duration.ofMinutes(5)
        ));
        assertThrows(IllegalArgumentException.class, () -> new ServiceJwtProperties(
                ISSUER,
                AUDIENCE,
                URI.create("https://issuer.example/jwks"),
                Set.of(),
                Duration.ofMinutes(5)
        ));
    }

    private static ServiceJwtProperties properties() {
        return new ServiceJwtProperties(
                ISSUER,
                AUDIENCE,
                URI.create("https://issuer.example/.well-known/jwks.json"),
                Set.of("gateway-service", "admin-service"),
                Duration.ofMinutes(5)
        );
    }

    private static Jwt token(String issuer, String audience, String subject, long expiresInSeconds) {
        Jwt.Builder builder = tokenBuilder(issuer, audience, expiresInSeconds);
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.build();
    }

    private static Jwt.Builder tokenBuilder(String issuer, String audience, long expiresInSeconds) {
        Instant issuedAt = expiresInSeconds < 0 ? NOW.minusSeconds(600) : NOW.minusSeconds(30);
        return Jwt.withTokenValue("test-service-token")
                .header("alg", "RS256")
                .issuer(issuer)
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(NOW.plusSeconds(expiresInSeconds));
    }
}
