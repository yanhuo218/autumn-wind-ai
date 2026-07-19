package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.security;

import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.configuration.ServiceJwtValidationProperties;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ServiceJwtValidator implements OAuth2TokenValidator<Jwt> {

    private static final Duration ALLOWED_CLOCK_SKEW = Duration.ofSeconds(60);
    private final OAuth2TokenValidator<Jwt> delegate;

    public ServiceJwtValidator(ServiceJwtValidationProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "Service JWT 配置不能为空。");
        Objects.requireNonNull(clock, "时钟不能为空。");
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator(ALLOWED_CLOCK_SKEW);
        timestampValidator.setClock(clock);
        delegate = new DelegatingOAuth2TokenValidator<>(
                timestampValidator,
                new JwtIssuerValidator(properties.issuer()),
                new JwtAudienceValidator(properties.audience()),
                new JwtClaimValidator<>("sub", subject -> subject instanceof String value
                        && properties.allowedCallers().contains(value)),
                new JwtClaimValidator<>("jti", value -> value instanceof String text && !text.isBlank()),
                token -> validateLifetime(token, properties.maximumLifetime(), clock.instant())
        );
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        return delegate.validate(token);
    }

    private static OAuth2TokenValidatorResult validateLifetime(Jwt token, Duration maximumLifetime, Instant now) {
        Instant issuedAt = token.getIssuedAt();
        Instant expiresAt = token.getExpiresAt();
        if (issuedAt == null || expiresAt == null || issuedAt.isAfter(now.plus(ALLOWED_CLOCK_SKEW))
                || !expiresAt.isAfter(now.minus(ALLOWED_CLOCK_SKEW)) || !expiresAt.isAfter(issuedAt)
                || Duration.between(issuedAt, expiresAt).compareTo(maximumLifetime) > 0) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", "Service JWT 有效期不合法。", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
