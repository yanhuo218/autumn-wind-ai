package io.github.yanhuo218.autumnwind.identity.infrastructure.configuration;

import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.infrastructure.security.IdentitySecurityErrorWriter;
import io.github.yanhuo218.autumnwind.identity.infrastructure.security.ServiceJwtValidator;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(ServiceJwtProperties.class)
public class InternalSecurityConfiguration {

    @Bean
    JwtDecoder serviceJwtDecoder(ServiceJwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new ServiceJwtValidator(properties, clock));
        return decoder;
    }

    @Bean
    @Order(1)
    SecurityFilterChain internalSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder serviceJwtDecoder,
            ServiceJwtProperties properties,
            IdentitySecurityErrorWriter errorWriter
    ) throws Exception {
        String requiredAuthority = "SCOPE_" + properties.requiredScope();

        http
                .securityMatcher("/internal/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context
                        .requireExplicitSave(true)
                        .securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/internal/v1/auth/session-introspections")
                        .hasAuthority(requiredAuthority)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(serviceJwtDecoder))
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            errorWriter.write(
                                    request,
                                    response,
                                    HttpServletResponse.SC_UNAUTHORIZED,
                                    IdentityErrorCode.INVALID_SERVICE_TOKEN,
                                    "Service JWT 无效或缺失。"
                            );
                        })
                        .accessDeniedHandler((request, response, exception) -> errorWriter.write(
                                request,
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                IdentityErrorCode.ACCESS_DENIED,
                                "当前服务无权执行该操作。"
                        )));

        return http.build();
    }
}
