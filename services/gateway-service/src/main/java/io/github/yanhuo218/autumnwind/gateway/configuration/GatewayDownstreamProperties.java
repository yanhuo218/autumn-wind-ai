package io.github.yanhuo218.autumnwind.gateway.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Objects;

@ConfigurationProperties("autumn-wind.gateway.downstream")
public record GatewayDownstreamProperties(URI identityBaseUrl, URI modelRegistryBaseUrl) {

    public GatewayDownstreamProperties {
        identityBaseUrl = requireSafeBaseUrl(identityBaseUrl);
        modelRegistryBaseUrl = requireSafeBaseUrl(modelRegistryBaseUrl);
    }

    private static URI requireSafeBaseUrl(URI value) {
        Objects.requireNonNull(value, "下游基础地址不能为空。");
        if (!value.isAbsolute()
                || value.getHost() == null
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null) {
            throw new IllegalArgumentException("下游基础地址格式不合法。");
        }
        if ("https".equalsIgnoreCase(value.getScheme())) {
            return value;
        }
        if ("http".equalsIgnoreCase(value.getScheme()) && isLoopbackAddressLiteral(value.getHost())) {
            return value;
        }
        throw new IllegalArgumentException("下游基础地址必须使用 HTTPS 或回环 HTTP 地址。");
    }

    private static boolean isLoopbackAddressLiteral(String host) {
        return "127.0.0.1".equals(host)
                || "[::1]".equalsIgnoreCase(host)
                || "[0:0:0:0:0:0:0:1]".equalsIgnoreCase(host);
    }
}
