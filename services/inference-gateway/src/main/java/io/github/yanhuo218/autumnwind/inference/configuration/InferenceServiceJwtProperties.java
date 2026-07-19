package io.github.yanhuo218.autumnwind.inference.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("autumn-wind.inference.service-jwt")
public record InferenceServiceJwtProperties(
        String issuer,
        Path privateKeyPath,
        Path publicKeyPath,
        String keyId,
        Duration lifetime
) {

    public static final String SUBJECT = "inference-gateway-service";

    public InferenceServiceJwtProperties {
        issuer = requireHttpsIssuer(issuer);
        privateKeyPath = requireNonBlankPath(privateKeyPath, "Inference Service JWT 私钥文件不能为空。");
        publicKeyPath = requireNonBlankPath(publicKeyPath, "Inference Service JWT 公钥文件不能为空。");
        keyId = requireText(keyId, "Inference Service JWT Key ID 不能为空。");
        if (!keyId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("Inference Service JWT Key ID 格式不合法。");
        }
        Objects.requireNonNull(lifetime, "Inference Service JWT 有效期不能为空。");
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("Inference Service JWT 有效期必须大于零且不超过 60 秒。");
        }
    }

    @Override
    public String toString() {
        return "InferenceServiceJwtProperties[issuer=<REDACTED>, privateKeyPath=<REDACTED>"
                + ", publicKeyPath=<REDACTED>, keyId=" + keyId + ", lifetime=" + lifetime + "]";
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String requireHttpsIssuer(String value) {
        String issuer = requireText(value, "Inference Service JWT issuer 不能为空。");
        URI uri = URI.create(issuer);
        if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Inference Service JWT issuer 必须是无用户信息、查询参数和片段的 HTTPS 地址。");
        }
        return uri.toString();
    }

    private static Path requireNonBlankPath(Path value, String message) {
        Objects.requireNonNull(value, message);
        if (value.toString().isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
