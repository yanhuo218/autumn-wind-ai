package io.github.yanhuo218.autumnwind.inference.configuration;

import io.github.yanhuo218.autumnwind.inference.registry.InferenceTargetClient;
import io.github.yanhuo218.autumnwind.inference.registry.ModelRegistryWebClient;
import io.github.yanhuo218.autumnwind.inference.security.NimbusServiceJwtIssuer;
import io.github.yanhuo218.autumnwind.inference.security.RsaKeyMaterial;
import io.github.yanhuo218.autumnwind.inference.security.RsaKeyMaterialLoader;
import io.github.yanhuo218.autumnwind.inference.security.ServiceJwtIssuer;
import io.github.yanhuo218.autumnwind.inference.security.ServiceJwtRequest;
import io.netty.channel.ChannelOption;
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
    HttpClient modelRegistryHttpClient(ModelRegistryClientProperties properties) {
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.timeout().toMillis()))
                .responseTimeout(properties.timeout());
    }

    @Bean
    InferenceTargetClient modelRegistryWebClient(
            ModelRegistryClientProperties properties,
            ServiceJwtIssuer serviceJwtIssuer,
            HttpClient modelRegistryHttpClient
    ) {
        WebClient webClient = WebClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .clientConnector(new ReactorClientHttpConnector(modelRegistryHttpClient))
                .build();
        return new ModelRegistryWebClient(webClient, ownerUserId -> Mono.fromSupplier(() -> serviceJwtIssuer.issue(
                ServiceJwtRequest.actor(
                        "model-registry-service",
                        "model-registry.inference.resolve",
                        ownerUserId))), properties.timeout());
    }
}
