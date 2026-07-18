package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Objects;

@ConfigurationProperties("autumn-wind.model-registry.secret-store")
public record ModelRegistrySecretStoreProperties(
        Path masterKeyFile,
        String keyId
) {

    public ModelRegistrySecretStoreProperties {
        Objects.requireNonNull(masterKeyFile, "Model Registry 主密钥文件不能为空。");
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("Model Registry 主密钥标识不能为空。");
        }
    }
}
