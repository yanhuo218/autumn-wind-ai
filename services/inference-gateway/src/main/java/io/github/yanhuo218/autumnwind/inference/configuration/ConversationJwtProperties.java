package io.github.yanhuo218.autumnwind.inference.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

@ConfigurationProperties("autumn-wind.inference.conversation-jwt")
public record ConversationJwtProperties(
        String issuer,
        String audience,
        URI jwkSetUri,
        Set<String> allowedCallers,
        Duration maximumLifetime
) {

    public static final String REQUIRED_CALLER = "conversation-service";
    public static final String REQUIRED_AUDIENCE = "inference-gateway";
    public static final String REQUIRED_JWT_ALGORITHM = "RS256";

    public ConversationJwtProperties {
        issuer = requireHttpsIssuer(issuer, "Conversation JWT issuer");
        audience = requireText(audience, "Conversation JWT audience 不能为空。");
        if (!REQUIRED_AUDIENCE.equals(audience)) {
            throw new IllegalArgumentException("Conversation JWT audience 必须固定为 inference-gateway。");
        }
        audience = REQUIRED_AUDIENCE;
        jwkSetUri = requireHttpsUri(jwkSetUri, "Conversation JWT JWK Set URI");
        if (!Set.of(REQUIRED_CALLER).equals(allowedCallers)) {
            throw new IllegalArgumentException("Conversation JWT 调用方必须固定为 conversation-service。");
        }
        allowedCallers = Set.of(REQUIRED_CALLER);
        Objects.requireNonNull(maximumLifetime, "Conversation JWT 最大有效期不能为空。");
        if (maximumLifetime.isNegative() || maximumLifetime.isZero()
                || maximumLifetime.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("Conversation JWT 最大有效期必须大于零且不超过 60 秒。");
        }
    }

    private static URI requireHttpsUri(URI value, String name) {
        Objects.requireNonNull(value, name + " 不能为空。");
        if (!value.isAbsolute() || !"https".equalsIgnoreCase(value.getScheme())
                || value.getHost() == null || value.getUserInfo() != null
                || value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException(name + " 必须是无用户信息、查询参数和片段的 HTTPS 地址。");
        }
        return value;
    }

    private static String requireHttpsIssuer(String value, String name) {
        String issuer = requireText(value, name + " 不能为空。");
        return requireHttpsUri(URI.create(issuer), name).toString();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
