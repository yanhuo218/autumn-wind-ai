package io.github.yanhuo218.autumnwind.inference.configuration;

import io.github.yanhuo218.autumnwind.inference.registry.InferenceTargetClient;
import io.github.yanhuo218.autumnwind.inference.registry.ModelRegistryWebClient;
import io.github.yanhuo218.autumnwind.inference.security.NimbusServiceJwtIssuer;
import io.github.yanhuo218.autumnwind.inference.security.RsaKeyMaterial;
import io.github.yanhuo218.autumnwind.inference.security.RsaKeyMaterialLoader;
import io.github.yanhuo218.autumnwind.inference.security.ServiceJwtIssuer;
import io.github.yanhuo218.autumnwind.inference.security.ServiceJwtRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.core.publisher.Mono;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({InferenceServiceJwtProperties.class, ModelRegistryClientProperties.class})
public class InferenceJwtConfiguration {

    @Bean
    RsaKeyMaterial inferenceRsaKeyMaterial(InferenceServiceJwtProperties properties) {
        return new RsaKeyMaterialLoader().load(
                properties.privateKeyPath(), properties.publicKeyPath(), properties.keyId());
    }

    @Bean
    Clock inferenceServiceJwtClock() {
        return Clock.systemUTC();
    }

    @Bean
    ServiceJwtIssuer inferenceServiceJwtIssuer(
            RsaKeyMaterial keyMaterial,
            InferenceServiceJwtProperties properties,
            Clock inferenceServiceJwtClock
    ) {
        return new NimbusServiceJwtIssuer(keyMaterial, properties, inferenceServiceJwtClock);
    }

    @Bean
    InferenceTargetClient modelRegistryWebClient(
            ModelRegistryClientProperties properties,
            ServiceJwtIssuer serviceJwtIssuer
    ) {
        WebClient webClient = WebClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(properties.timeout())))
                .build();
        return new ModelRegistryWebClient(webClient, ownerUserId -> Mono.fromSupplier(() -> serviceJwtIssuer.issue(
                ServiceJwtRequest.actor(
                        "model-registry-service",
                        "model-registry.inference.resolve",
                        ownerUserId))));
    }
}
