package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.configuration;

import io.github.yanhuo218.autumnwind.security.secrets.AesGcmSecretStore;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(ModelRegistrySecretStoreProperties.class)
public class ModelRegistryApplicationConfiguration {

    @Bean
    Clock modelRegistryClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecretStore modelRegistrySecretStore(ModelRegistrySecretStoreProperties properties) {
        return AesGcmSecretStore.fromBase64File(properties.masterKeyFile(), properties.keyId());
    }
}
