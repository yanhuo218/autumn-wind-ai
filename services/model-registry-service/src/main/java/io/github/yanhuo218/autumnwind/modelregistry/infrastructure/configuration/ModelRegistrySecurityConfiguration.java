package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.configuration;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ModelRegistryErrorCode;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.security.ModelRegistrySecurityErrorWriter;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.security.ServiceJwtValidator;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
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

import java.net.URI;
import java.time.Clock;
import java.util.UUID;

@Configuration
@EnableConfigurationProperties({ServiceJwtProperties.class, InferenceJwtProperties.class})
public class ModelRegistrySecurityConfiguration {

    private static final String ENDPOINT_MANAGE_AUTHORITY = "SCOPE_model-registry.endpoint.manage";
    private static final String MODEL_READ_AUTHORITY = "SCOPE_model-registry.model.read";
    private static final String MODEL_MANAGE_AUTHORITY = "SCOPE_model-registry.model.manage";
    private static final String INFERENCE_RESOLVE_AUTHORITY = "SCOPE_model-registry.inference.resolve";
    private static final String CONNECTION_TEST_EXECUTE_AUTHORITY =
            "SCOPE_model-registry.connection-test.execute";

    @Bean("modelRegistryServiceJwtDecoder")
    JwtDecoder modelRegistryServiceJwtDecoder(ServiceJwtProperties properties, Clock clock) {
        return decoder(properties.jwkSetUri(), new ServiceJwtValidator(properties, clock));
    }

    @Bean("inferenceJwtDecoder")
    JwtDecoder inferenceJwtDecoder(InferenceJwtProperties properties, Clock clock) {
        return decoder(properties.jwkSetUri(), new ServiceJwtValidator(properties, clock));
    }

    @Bean
    @Order(1)
    SecurityFilterChain modelRegistryInternalSecurityFilterChain(
            HttpSecurity http,
            @Qualifier("inferenceJwtDecoder") JwtDecoder decoder,
            ModelRegistrySecurityErrorWriter errorWriter
    ) throws Exception {
        http.securityMatcher("/internal/v1/model-registry/**");
        configureStateless(http, decoder, errorWriter);
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/internal/v1/model-registry/inference-target-resolutions")
                .access((authentication, context) -> new AuthorizationDecision(
                        mayResolveInferenceTarget(authentication.get())))
                .requestMatchers(HttpMethod.POST, "/internal/v1/model-registry/connection-test-jobs/**")
                .access((authentication, context) -> new AuthorizationDecision(
                        mayExecuteConnectionTest(authentication.get())))
                .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain modelRegistryPublicSecurityFilterChain(
            HttpSecurity http,
            @Qualifier("modelRegistryServiceJwtDecoder") JwtDecoder decoder,
            ModelRegistrySecurityErrorWriter errorWriter
    ) throws Exception {
        http.securityMatcher("/api/v1/model-registry/**");
        configureStateless(http, decoder, errorWriter);
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/v1/model-registry/endpoints",
                        "/api/v1/model-registry/endpoints/**")
                .access((authentication, context) -> new AuthorizationDecision(
                        mayManageEndpoints(authentication.get())))
                .requestMatchers(HttpMethod.POST, "/api/v1/model-registry/endpoints")
                .access((authentication, context) -> new AuthorizationDecision(
                        mayManageEndpoints(authentication.get())))
                .requestMatchers(HttpMethod.POST,
                        "/api/v1/model-registry/endpoints/*/connection-tests")
                .access((authentication, context) -> new AuthorizationDecision(
                        mayManageEndpoints(authentication.get())))
                .requestMatchers(HttpMethod.PUT, "/api/v1/model-registry/endpoints/*/credential")
                .access((authentication, context) -> new AuthorizationDecision(
                        mayManageEndpoints(authentication.get())))
                .requestMatchers(HttpMethod.GET, "/api/v1/model-registry/models",
                        "/api/v1/model-registry/models/*")
                .access((authentication, context) -> new AuthorizationDecision(
                        mayReadModels(authentication.get())))
                .requestMatchers(HttpMethod.POST, "/api/v1/model-registry/models")
                .access((authentication, context) -> new AuthorizationDecision(
                        mayManageModels(authentication.get())))
                .requestMatchers(HttpMethod.PUT, "/api/v1/model-registry/models/*")
                .access((authentication, context) -> new AuthorizationDecision(
                        mayManageModels(authentication.get())))
                .anyRequest().denyAll());
        return http.build();
    }

    private static JwtDecoder decoder(URI jwkSetUri, ServiceJwtValidator validator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri.toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(validator);
        return decoder;
    }

    private static void configureStateless(
            HttpSecurity http,
            JwtDecoder decoder,
            ModelRegistrySecurityErrorWriter errorWriter
    ) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.requireExplicitSave(true)
                        .securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(decoder))
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            errorWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                                    ModelRegistryErrorCode.INVALID_SERVICE_TOKEN, "Service JWT 无效或缺失。");
                        })
                        .accessDeniedHandler((request, response, exception) -> errorWriter.write(
                                request, response, HttpServletResponse.SC_FORBIDDEN,
                                ModelRegistryErrorCode.ACCESS_DENIED, "当前服务无权执行该操作。")));
    }

    private static boolean mayManageEndpoints(Authentication authentication) {
        return mayManage(authentication, ENDPOINT_MANAGE_AUTHORITY);
    }

    private static boolean mayManageModels(Authentication authentication) {
        return mayManage(authentication, MODEL_MANAGE_AUTHORITY);
    }

    private static boolean mayReadModels(Authentication authentication) {
        return mayManage(authentication, MODEL_READ_AUTHORITY)
                || mayManageModels(authentication);
    }

    private static boolean mayResolveInferenceTarget(Authentication authentication) {
        return mayManage(authentication, INFERENCE_RESOLVE_AUTHORITY);
    }

    private static boolean mayExecuteConnectionTest(Authentication authentication) {
        return hasAuthority(authentication, CONNECTION_TEST_EXECUTE_AUTHORITY);
    }

    private static boolean mayManage(Authentication authentication, String requiredAuthority) {
        if (!hasAuthority(authentication, requiredAuthority)) {
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

    private static boolean hasAuthority(Authentication authentication, String requiredAuthority) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> requiredAuthority.equals(authority.getAuthority()));
    }
}
