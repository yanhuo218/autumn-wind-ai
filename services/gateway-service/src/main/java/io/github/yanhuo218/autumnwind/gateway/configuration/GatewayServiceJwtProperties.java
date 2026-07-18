package io.github.yanhuo218.autumnwind.gateway.configuration;

import io.github.yanhuo218.autumnwind.gateway.security.RsaKeyMaterial;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("service-jwt")
public record GatewayServiceJwtProperties(
        String issuer,
        Path privateKeyPath,
        Path publicKeyPath,
        String keyId,
        Duration lifetime
) {

    private static final Duration REQUIRED_LIFETIME = Duration.ofSeconds(60);

    public GatewayServiceJwtProperties {
        issuer = requireText(issuer, "Service JWT issuer 不能为空。");
        privateKeyPath = Objects.requireNonNull(privateKeyPath, "Service JWT 私钥路径不能为空。");
        publicKeyPath = Objects.requireNonNull(publicKeyPath, "Service JWT 公钥路径不能为空。");
        keyId = RsaKeyMaterial.requireValidKeyId(keyId);
        if (!REQUIRED_LIFETIME.equals(lifetime)) {
            throw new IllegalArgumentException("Service JWT 有效期必须为 60 秒。");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
