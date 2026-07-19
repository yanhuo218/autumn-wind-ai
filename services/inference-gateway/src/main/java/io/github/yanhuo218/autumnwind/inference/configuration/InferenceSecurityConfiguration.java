package io.github.yanhuo218.autumnwind.inference.configuration;

import io.github.yanhuo218.autumnwind.inference.security.ConversationServiceJwtValidator;
import io.github.yanhuo218.autumnwind.inference.security.InferenceSecurityErrorWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;

import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@EnableConfigurationProperties(ConversationJwtProperties.class)
public class InferenceSecurityConfiguration {

    private static final String INVOKE_AUTHORITY = "SCOPE_inference.chat.invoke";

    @Bean(name = "conversationServiceJwtDecoder")
    ReactiveJwtDecoder conversationServiceJwtDecoder(ConversationJwtProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new ConversationServiceJwtValidator(properties, Clock.systemUTC()));
        return decoder;
    }

    @Bean
    @Order(1)
    public SecurityWebFilterChain inferenceInternalSecurityWebFilterChain(
            ServerHttpSecurity http,
            @Qualifier("conversationServiceJwtDecoder") ReactiveJwtDecoder decoder,
            InferenceSecurityErrorWriter errorWriter
    ) {
        return http.securityMatcher(new PathPatternParserServerWebExchangeMatcher("/internal/v1/inference/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(authorize -> authorize
                        .pathMatchers(HttpMethod.POST, "/internal/v1/inference/chat-completions")
                        .access((authentication, context) -> authentication
                                .map(InferenceSecurityConfiguration::mayInvoke)
                                .map(AuthorizationDecision::new))
                        .anyExchange().denyAll())
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtDecoder(decoder))
                        .authenticationEntryPoint(errorWriter::writeUnauthorized)
                        .accessDeniedHandler(errorWriter::writeForbidden))
                .build();
    }

    private static boolean mayInvoke(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream().noneMatch(
                authority -> INVOKE_AUTHORITY.equals(authority.getAuthority()))) {
            return false;
        }
        Object actor = token.getToken().getClaims().get("actor_user_id");
        return actor instanceof String value && isCanonicalUuid(value);
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
