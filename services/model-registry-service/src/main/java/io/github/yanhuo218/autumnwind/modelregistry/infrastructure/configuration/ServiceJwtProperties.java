package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

@ConfigurationProperties("autumn-wind.model-registry.service-jwt")
public record ServiceJwtProperties(
        String issuer,
        String audience,
        URI jwkSetUri,
        Set<String> allowedCallers,
        Duration maximumLifetime
) {

    public ServiceJwtProperties {
        issuer = requireText(issuer, "Service JWT issuer 不能为空。");
        audience = requireText(audience, "Service JWT audience 不能为空。");
        Objects.requireNonNull(maximumLifetime, "Service JWT 最大有效期不能为空。");
        if (maximumLifetime.isNegative() || maximumLifetime.isZero()
                || maximumLifetime.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Service JWT 最大有效期必须大于零且不超过 1 小时。");
        }
        Objects.requireNonNull(jwkSetUri, "Service JWT JWK Set URI 不能为空。");
        if (!jwkSetUri.isAbsolute() || !"https".equalsIgnoreCase(jwkSetUri.getScheme())
                || jwkSetUri.getHost() == null || jwkSetUri.getUserInfo() != null
                || jwkSetUri.getFragment() != null) {
            throw new IllegalArgumentException("Service JWT JWK Set URI 必须是无用户信息和片段的 HTTPS 地址。");
        }
        if (allowedCallers == null || allowedCallers.isEmpty()) {
            throw new IllegalArgumentException("Service JWT 允许调用方不能为空。");
        }
        Set<String> normalizedCallers = new LinkedHashSet<>();
        for (String caller : allowedCallers) {
            String normalizedCaller = requireText(caller, "Service JWT 调用方标识不能为空。");
            if (normalizedCaller.length() > 256 || normalizedCaller.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Service JWT 调用方标识格式不合法。");
            }
            normalizedCallers.add(normalizedCaller);
        }
        allowedCallers = Set.copyOf(normalizedCallers);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
