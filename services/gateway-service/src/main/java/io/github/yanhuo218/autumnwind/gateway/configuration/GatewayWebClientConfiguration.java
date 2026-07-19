package io.github.yanhuo218.autumnwind.gateway.configuration;

import io.github.yanhuo218.autumnwind.gateway.identity.IdentityAuthProxyClient;
import io.github.yanhuo218.autumnwind.gateway.identity.IdentitySessionClient;
import io.github.yanhuo218.autumnwind.gateway.security.ServiceJwtIssuer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class GatewayWebClientConfiguration {

    private static final int MAX_RESPONSE_BODY_BYTES = 1024 * 1024;
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(5);

    @Bean("identityWebClient")
    public WebClient identityWebClient(GatewayDownstreamProperties properties) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BODY_BYTES))
                .build();
        HttpClient httpClient = HttpClient.create().responseTimeout(TOTAL_TIMEOUT);
        return WebClient.builder()
                .baseUrl(properties.identityBaseUrl().toString())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }

    @Bean
    public IdentityAuthProxyClient identityAuthProxyClient(@Qualifier("identityWebClient") WebClient identityWebClient) {
        return IdentityAuthProxyClient.webClientBacked(identityWebClient);
    }

    @Bean
    public IdentitySessionClient identitySessionClient(
            @Qualifier("identityWebClient") WebClient identityWebClient,
            ServiceJwtIssuer serviceJwtIssuer,
            Clock serviceJwtClock
    ) {
        return IdentitySessionClient.webClientBacked(identityWebClient, serviceJwtIssuer, serviceJwtClock);
    }
}
