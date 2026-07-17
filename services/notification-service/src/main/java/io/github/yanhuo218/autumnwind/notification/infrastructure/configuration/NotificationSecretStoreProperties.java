package io.github.yanhuo218.autumnwind.notification.infrastructure.configuration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

@Validated
@ConfigurationProperties("autumn-wind.notification.secret-store")
public record NotificationSecretStoreProperties(
        @NotNull Path masterKeyFile,
        @NotBlank String keyId
) {
}
