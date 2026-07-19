package io.github.yanhuo218.autumnwind.inference.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Objects;

@ConfigurationProperties("autumn-wind.inference.secret-store")
public record InferenceSecretStoreProperties(Path masterKeyFile, String keyId) {

    public InferenceSecretStoreProperties {
        Objects.requireNonNull(masterKeyFile, "Inference 主密钥文件不能为空。");
        if (masterKeyFile.toString().isBlank()) {
            throw new IllegalArgumentException("Inference 主密钥文件不能为空。");
        }
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("Inference 主密钥 Key ID 不能为空。");
        }
        keyId = keyId.trim();
    }
}
