package io.github.yanhuo218.autumnwind.gateway.configuration;

import io.github.yanhuo218.autumnwind.gateway.security.GatewaySessionAuthenticationWebFilter;
import io.github.yanhuo218.autumnwind.gateway.identity.IdentitySessionClient;
import io.github.yanhuo218.autumnwind.gateway.security.SessionCookieExtractor;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorCode;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorResponseWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class GatewaySecurityConfiguration {

    @Bean
    public SecurityWebFilterChain gatewaySecurityWebFilterChain(
            ServerHttpSecurity http,
            GatewayErrorResponseWriter errorResponseWriter,
            GatewaySessionAuthenticationWebFilter gatewaySessionAuthenticationWebFilter
    ) {
        return http
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .addFilterBefore(gatewaySessionAuthenticationWebFilter, SecurityWebFiltersOrder.AUTHORIZATION)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((exchange, error) -> errorResponseWriter.write(
                                exchange,
                                HttpStatus.FORBIDDEN,
                                GatewayErrorCode.ROUTE_NOT_ALLOWED,
                                "当前路由不允许访问。"))
                        .accessDeniedHandler((exchange, error) -> errorResponseWriter.write(
                                exchange,
                                HttpStatus.FORBIDDEN,
                                GatewayErrorCode.ROUTE_NOT_ALLOWED,
                                "当前路由不允许访问。")))
                .authorizeExchange(authorize -> authorize
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/auth/csrf",
                                "/api/v1/auth/registration-options",
                                "/api/v1/auth/session",
                                "/internal/v1/security/jwks",
                                "/actuator/health",
                                "/actuator/info",
                                "/api/v1/model-registry/models")
                        .permitAll()
                        .pathMatchers(HttpMethod.POST,
                                "/api/v1/auth/registrations",
                                "/api/v1/auth/sessions")
                        .permitAll()
                        .pathMatchers(HttpMethod.DELETE, "/api/v1/auth/session")
                        .permitAll()
                        .anyExchange().denyAll())
                .build();
    }

    @Bean
    public GatewaySessionAuthenticationWebFilter gatewaySessionAuthenticationWebFilter(
            IdentitySessionClient identitySessionClient,
            GatewayErrorResponseWriter errorResponseWriter
    ) {
        return new GatewaySessionAuthenticationWebFilter(
                new SessionCookieExtractor(), identitySessionClient, errorResponseWriter);
    }
}
