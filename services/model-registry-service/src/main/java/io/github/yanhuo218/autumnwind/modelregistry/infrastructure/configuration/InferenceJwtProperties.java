package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

@ConfigurationProperties("autumn-wind.model-registry.inference-jwt")
public record InferenceJwtProperties(
        String issuer,
        String audience,
        URI jwkSetUri,
        Set<String> allowedCallers,
        Duration maximumLifetime
) implements ServiceJwtValidationProperties {

    public static final String REQUIRED_CALLER = "inference-gateway-service";

    public InferenceJwtProperties {
        issuer = requireText(issuer, "Inference JWT issuer 不能为空。");
        audience = requireText(audience, "Inference JWT audience 不能为空。");
        Objects.requireNonNull(maximumLifetime, "Inference JWT 最大有效期不能为空。");
        if (maximumLifetime.isNegative() || maximumLifetime.isZero()
                || maximumLifetime.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("Inference JWT 最大有效期必须大于零且不超过 60 秒。");
        }
        Objects.requireNonNull(jwkSetUri, "Inference JWT JWK Set URI 不能为空。");
        if (!jwkSetUri.isAbsolute() || !"https".equalsIgnoreCase(jwkSetUri.getScheme())
                || jwkSetUri.getHost() == null || jwkSetUri.getUserInfo() != null
                || jwkSetUri.getFragment() != null) {
            throw new IllegalArgumentException("Inference JWT JWK Set URI 必须是无用户信息和片段的 HTTPS 地址。");
        }
        if (allowedCallers == null || allowedCallers.isEmpty()) {
            throw new IllegalArgumentException("Inference JWT 允许调用方不能为空。");
        }
        Set<String> normalizedCallers = new LinkedHashSet<>();
        for (String caller : allowedCallers) {
            String normalizedCaller = requireText(caller, "Inference JWT 调用方标识不能为空。");
            if (normalizedCaller.length() > 256 || normalizedCaller.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Inference JWT 调用方标识格式不合法。");
            }
            normalizedCallers.add(normalizedCaller);
        }
        if (!normalizedCallers.equals(Set.of(REQUIRED_CALLER))) {
            throw new IllegalArgumentException("Inference JWT 仅允许 inference-gateway-service 调用。");
        }
        allowedCallers = Set.copyOf(normalizedCallers);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    @Override
    public boolean requiresUniqueAudience() {
        return true;
    }
}
