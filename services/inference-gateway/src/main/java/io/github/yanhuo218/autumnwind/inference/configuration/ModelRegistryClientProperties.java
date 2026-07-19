package io.github.yanhuo218.autumnwind.inference.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("autumn-wind.inference.model-registry")
public record ModelRegistryClientProperties(
        URI baseUrl,
        Duration timeout,
        boolean allowLoopbackHttpForTest
) {

    public ModelRegistryClientProperties {
        baseUrl = requireSafeBaseUrl(baseUrl, allowLoopbackHttpForTest);
        Objects.requireNonNull(timeout, "Model Registry 超时不能为空。");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Model Registry 超时必须为 1 到 30 秒。");
        }
    }

    private static URI requireSafeBaseUrl(URI value, boolean allowLoopbackHttpForTest) {
        Objects.requireNonNull(value, "Model Registry 基础地址不能为空。");
        if (!value.isAbsolute() || value.getHost() == null || value.getUserInfo() != null
                || value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("Model Registry 基础地址格式不合法。");
        }
        if ("https".equalsIgnoreCase(value.getScheme())) {
            return value;
        }
        if (allowLoopbackHttpForTest && "http".equalsIgnoreCase(value.getScheme())
                && isLoopbackHost(value.getHost())) {
            return value;
        }
        throw new IllegalArgumentException("Model Registry 基础地址必须使用 HTTPS，测试时仅允许回环 HTTP 地址。");
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equalsIgnoreCase(host);
    }
}
