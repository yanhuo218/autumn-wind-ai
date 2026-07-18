package io.github.yanhuo218.autumnwind.notification.infrastructure.configuration;

import io.github.yanhuo218.autumnwind.notification.application.NotificationErrorCode;
import io.github.yanhuo218.autumnwind.notification.infrastructure.security.NotificationSecurityErrorWriter;
import io.github.yanhuo218.autumnwind.notification.infrastructure.security.ServiceJwtValidator;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;

import java.time.Clock;
import java.util.UUID;

@Configuration
@EnableConfigurationProperties(ServiceJwtProperties.class)
public class NotificationSecurityConfiguration {

    private static final String SMTP_MANAGE_AUTHORITY = "SCOPE_notification.smtp.manage";

    @Bean
    JwtDecoder notificationServiceJwtDecoder(ServiceJwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new ServiceJwtValidator(properties, clock));
        return decoder;
    }

    @Bean
    @Order(1)
    SecurityFilterChain notificationAdminSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder notificationServiceJwtDecoder,
            NotificationSecurityErrorWriter errorWriter
    ) throws Exception {
        http
                .securityMatcher("/api/v1/admin/notification/**")
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
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/notification/smtp-config")
                        .hasAuthority(SMTP_MANAGE_AUTHORITY)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/notification/smtp-config")
                        .access((authentication, context) -> new AuthorizationDecision(
                                mayManageSmtp(authentication.get())
                        ))
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/notification/test-emails")
                        .access((authentication, context) -> new AuthorizationDecision(
                                mayManageSmtp(authentication.get())
                        ))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(notificationServiceJwtDecoder))
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            errorWriter.write(
                                    request,
                                    response,
                                    HttpServletResponse.SC_UNAUTHORIZED,
                                    NotificationErrorCode.INVALID_SERVICE_TOKEN,
                                    "Service JWT 无效或缺失。"
                            );
                        })
                        .accessDeniedHandler((request, response, exception) -> errorWriter.write(
                                request,
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                NotificationErrorCode.ACCESS_DENIED,
                                "当前服务无权执行该操作。"
                        )));

        return http.build();
    }

    private static boolean mayManageSmtp(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities().stream()
                .noneMatch(authority -> SMTP_MANAGE_AUTHORITY.equals(authority.getAuthority()))) {
            return false;
        }
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return false;
        }
        Object claim = jwtAuthentication.getToken().getClaims().get("actor_user_id");
        if (!(claim instanceof String actorUserId)) {
            return false;
        }
        try {
            UUID parsedActorUserId = UUID.fromString(actorUserId);
            return parsedActorUserId.toString().equalsIgnoreCase(actorUserId);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
