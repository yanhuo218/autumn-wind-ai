package io.github.yanhuo218.autumnwind.notification.infrastructure.configuration;

import io.github.yanhuo218.autumnwind.security.secrets.AesGcmSecretStore;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(NotificationSecretStoreProperties.class)
public class NotificationApplicationConfiguration {

    @Bean
    Clock notificationClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecretStore notificationSecretStore(NotificationSecretStoreProperties properties) {
        return AesGcmSecretStore.fromBase64File(properties.masterKeyFile(), properties.keyId());
    }
}
