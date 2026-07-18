package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.configuration;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ModelRegistryErrorCode;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.security.ModelRegistrySecurityErrorWriter;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.security.ServiceJwtValidator;
import io.github.yanhuo218.autumnwind.modelregistry.interfaces.http.CorrelationIdFilter;
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
public class ModelRegistrySecurityConfiguration {

    private static final String ENDPOINT_MANAGE_AUTHORITY = "SCOPE_model-registry.endpoint.manage";
    private static final String MODEL_MANAGE_AUTHORITY = "SCOPE_model-registry.model.manage";

    @Bean
    JwtDecoder modelRegistryServiceJwtDecoder(ServiceJwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new ServiceJwtValidator(properties, clock));
        return decoder;
    }

    @Bean
    @Order(1)
    SecurityFilterChain modelRegistrySecurityFilterChain(
            HttpSecurity http,
            JwtDecoder modelRegistryServiceJwtDecoder,
            ModelRegistrySecurityErrorWriter errorWriter
    ) throws Exception {
        http
                .securityMatcher("/api/v1/model-registry/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.requireExplicitSave(true)
                        .securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/model-registry/endpoints",
                                "/api/v1/model-registry/endpoints/**")
                        .access((authentication, context) -> new AuthorizationDecision(
                                mayManageEndpoints(authentication.get())))
                        .requestMatchers(HttpMethod.POST, "/api/v1/model-registry/endpoints")
                        .access((authentication, context) -> new AuthorizationDecision(
                                mayManageEndpoints(authentication.get())))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/model-registry/endpoints/*/credential")
                        .access((authentication, context) -> new AuthorizationDecision(
                                mayManageEndpoints(authentication.get())))
                        .requestMatchers(HttpMethod.GET, "/api/v1/model-registry/models",
                                "/api/v1/model-registry/models/*")
                        .access((authentication, context) -> new AuthorizationDecision(
                                mayManageModels(authentication.get())))
                        .requestMatchers(HttpMethod.POST, "/api/v1/model-registry/models")
                        .access((authentication, context) -> new AuthorizationDecision(
                                mayManageModels(authentication.get())))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/model-registry/models/*")
                        .access((authentication, context) -> new AuthorizationDecision(
                                mayManageModels(authentication.get())))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(modelRegistryServiceJwtDecoder))
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            errorWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                                    ModelRegistryErrorCode.INVALID_SERVICE_TOKEN, "Service JWT 无效或缺失。");
                        })
                        .accessDeniedHandler((request, response, exception) -> errorWriter.write(
                                request, response, HttpServletResponse.SC_FORBIDDEN,
                                ModelRegistryErrorCode.ACCESS_DENIED, "当前服务无权执行该操作。")));
        return http.build();
    }

    private static boolean mayManageEndpoints(Authentication authentication) {
        return mayManage(authentication, ENDPOINT_MANAGE_AUTHORITY);
    }

    private static boolean mayManageModels(Authentication authentication) {
        return mayManage(authentication, MODEL_MANAGE_AUTHORITY);
    }

    private static boolean mayManage(Authentication authentication, String requiredAuthority) {
        if (authentication == null || authentication.getAuthorities().stream()
                .noneMatch(authority -> requiredAuthority.equals(authority.getAuthority()))) {
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
            UUID parsed = UUID.fromString(actorUserId);
            return parsed.toString().equalsIgnoreCase(actorUserId);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
