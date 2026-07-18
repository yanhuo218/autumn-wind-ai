package io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public record EndpointSettings(
        String displayName,
        URI baseUrl,
        EndpointProtocol protocol,
        Duration requestTimeout,
        boolean enabled
) {

    private static final int DISPLAY_NAME_MAX_CODE_POINTS = 100;
    private static final Duration MINIMUM_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(120);

    public EndpointSettings {
        displayName = normalizeDisplayName(displayName);
        baseUrl = validateBaseUrl(baseUrl);
        Objects.requireNonNull(protocol, "端点协议不能为空。");
        requestTimeout = validateTimeout(requestTimeout);
    }

    private static String normalizeDisplayName(String value) {
        if (value == null) {
            throw new IllegalArgumentException("端点显示名称不能为空。");
        }
        String normalized = value.strip();
        if (normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length()) > DISPLAY_NAME_MAX_CODE_POINTS
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("端点显示名称不能为空或超过 100 个字符。");
        }
        return normalized;
    }

    private static URI validateBaseUrl(URI value) {
        Objects.requireNonNull(value, "端点 Base URL 不能为空。");
        if (!"https".equalsIgnoreCase(value.getScheme())
                || value.getHost() == null
                || value.getHost().isBlank()
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null
                || containsControlCharacter(value.getPath())) {
            throw new IllegalArgumentException("端点 Base URL 必须是无凭据、查询和片段的有效 HTTPS 地址。");
        }
        return value;
    }

    private static boolean containsControlCharacter(String value) {
        return value != null && value.codePoints().anyMatch(Character::isISOControl);
    }

    private static Duration validateTimeout(Duration value) {
        Objects.requireNonNull(value, "端点请求超时不能为空。");
        if (value.compareTo(MINIMUM_TIMEOUT) < 0 || value.compareTo(MAXIMUM_TIMEOUT) > 0) {
            throw new IllegalArgumentException("端点请求超时必须在 1 到 120 秒之间。");
        }
        return value;
    }
}
