package io.github.yanhuo218.autumnwind.gateway.configuration;

import io.github.yanhuo218.autumnwind.gateway.security.NimbusServiceJwtIssuer;
import io.github.yanhuo218.autumnwind.gateway.security.RsaKeyMaterial;
import io.github.yanhuo218.autumnwind.gateway.security.RsaKeyMaterialLoader;
import io.github.yanhuo218.autumnwind.gateway.security.ServiceJwtIssuer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayServiceJwtProperties.class)
public class ServiceJwtConfiguration {

    @Bean
    RsaKeyMaterial gatewayRsaKeyMaterial(GatewayServiceJwtProperties properties) {
        return new RsaKeyMaterialLoader().load(
                properties.privateKeyPath(), properties.publicKeyPath(), properties.keyId());
    }

    @Bean
    Clock serviceJwtClock() {
        return Clock.systemUTC();
    }

    @Bean
    ServiceJwtIssuer serviceJwtIssuer(
            RsaKeyMaterial keyMaterial,
            GatewayServiceJwtProperties properties,
            Clock serviceJwtClock
    ) {
        return new NimbusServiceJwtIssuer(keyMaterial, properties, serviceJwtClock);
    }
}
