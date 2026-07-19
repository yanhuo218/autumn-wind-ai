package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.inference.InferenceTargetResolutionService;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.configuration.InferenceJwtProperties;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.configuration.ModelRegistrySecurityConfiguration;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.configuration.ServiceJwtProperties;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.security.ModelRegistrySecurityErrorWriter;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.security.ServiceJwtValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InferenceTargetResolutionController.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({ModelRegistrySecurityConfiguration.class, ModelRegistrySecurityErrorWriter.class, CorrelationIdFilter.class})
@TestPropertySource(properties = {
        "autumn-wind.model-registry.service-jwt.issuer=https://issuer.example",
        "autumn-wind.model-registry.service-jwt.audience=model-registry-service",
        "autumn-wind.model-registry.service-jwt.jwk-set-uri=https://issuer.example/.well-known/jwks.json",
        "autumn-wind.model-registry.service-jwt.allowed-callers=gateway-service,admin-service",
        "autumn-wind.model-registry.service-jwt.maximum-lifetime=PT5M",
        "autumn-wind.model-registry.inference-jwt.issuer=https://inference.internal",
        "autumn-wind.model-registry.inference-jwt.audience=model-registry-service",
        "autumn-wind.model-registry.inference-jwt.jwk-set-uri=https://inference.internal/internal/v1/security/jwks",
        "autumn-wind.model-registry.inference-jwt.allowed-callers=inference-gateway-service",
        "autumn-wind.model-registry.inference-jwt.maximum-lifetime=PT60S"
})
class ModelRegistryInternalSecurityTest {

    private static final String PATH = "/internal/v1/model-registry/inference-target-resolutions";
    private static final String ACTOR_ID = "f7590cc5-1e56-4a28-ac97-e58380a6d94e";
    private static final String REQUEST_BODY = "{\"ownerUserId\":\"" + ACTOR_ID
            + "\",\"modelId\":\"b88e1f00-83dc-4cf0-a7b3-000000000001\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InferenceTargetResolutionService resolutionService;

    @MockitoBean(name = "inferenceJwtDecoder")
    private JwtDecoder inferenceJwtDecoder;

    @MockitoBean(name = "modelRegistryServiceJwtDecoder")
    private JwtDecoder modelRegistryServiceJwtDecoder;

    @Test
    void 推理令牌仅由内部链验证并且公共链拒绝() throws Exception {
        when(inferenceJwtDecoder.decode("inference-token")).thenReturn(inferenceJwt());
        when(modelRegistryServiceJwtDecoder.decode("inference-token"))
                .thenThrow(new BadJwtException("公共信任域不接受推理令牌。"));

        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer inference-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/model-registry/models")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer inference-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-AUTH-0001"));
    }

    @Test
    void 内部错误Subject返回401() throws Exception {
        when(inferenceJwtDecoder.decode("invalid-inference-subject"))
                .thenThrow(new BadJwtException("内部调用方不受信任。"));

        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-inference-subject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-AUTH-0001"));
    }

    @Test
    void 正确专用Scope和操作者声明可以解析推理目标() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(jwt()
                                .jwt(jwt -> jwt.claim("actor_user_id", ACTOR_ID))
                                .authorities(new SimpleGrantedAuthority("SCOPE_model-registry.inference.resolve")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk());

        verify(resolutionService).resolve(
                java.util.UUID.fromString(ACTOR_ID),
                java.util.UUID.fromString("b88e1f00-83dc-4cf0-a7b3-000000000001")
        );
    }

    @Test
    void 缺少专用Scope返回403() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(jwt()
                                .jwt(jwt -> jwt.claim("actor_user_id", "f7590cc5-1e56-4a28-ac97-e58380a6d94e"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_model-registry.model.manage")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-FORBIDDEN-0001"));

        verify(resolutionService, never()).resolve(any(), any());
    }

    @Test
    void 缺少操作者声明时先于正文解析返回403() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("SCOPE_model-registry.inference.resolve")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-FORBIDDEN-0001"));

        verify(resolutionService, never()).resolve(any(), any());
    }

    @Test
    void 内部Validator拒绝包含额外受众的令牌() {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        InferenceJwtProperties properties = new InferenceJwtProperties(
                "https://inference.internal",
                "model-registry-service",
                URI.create("https://inference.internal/internal/v1/security/jwks"),
                Set.of("inference-gateway-service"),
                Duration.ofSeconds(60));
        ServiceJwtValidator validator = new ServiceJwtValidator(
                properties, Clock.fixed(now, ZoneOffset.UTC));
        Jwt token = Jwt.withTokenValue("inference-token-placeholder")
                .header("alg", "RS256")
                .issuer("https://inference.internal")
                .subject("inference-gateway-service")
                .audience(List.of("model-registry-service", "other-service"))
                .issuedAt(now.minusSeconds(10))
                .expiresAt(now.plusSeconds(20))
                .claim("jti", "inference-jti-placeholder")
                .build();

        assertTrue(validator.validate(token).hasErrors());
    }

    @Test
    void 公共Validator保持原有的目标受众包含语义() {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        ServiceJwtProperties properties = new ServiceJwtProperties(
                "https://gateway.internal",
                "model-registry-service",
                URI.create("https://gateway.internal/internal/v1/security/jwks"),
                Set.of("gateway-service"),
                Duration.ofMinutes(5));
        ServiceJwtValidator validator = new ServiceJwtValidator(
                properties, Clock.fixed(now, ZoneOffset.UTC));
        Jwt token = Jwt.withTokenValue("gateway-token-placeholder")
                .header("alg", "RS256")
                .issuer("https://gateway.internal")
                .subject("gateway-service")
                .audience(List.of("model-registry-service", "other-service"))
                .issuedAt(now.minusSeconds(10))
                .expiresAt(now.plusSeconds(20))
                .claim("jti", "gateway-jti-placeholder")
                .build();

        assertFalse(validator.validate(token).hasErrors());
    }

    private static Jwt inferenceJwt() {
        java.time.Instant issuedAt = java.time.Instant.now();
        return Jwt.withTokenValue("inference-token")
                .header("alg", "RS256")
                .issuer("https://inference.internal")
                .subject("inference-gateway-service")
                .audience(java.util.List.of("model-registry-service"))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(30))
                .claim("jti", "inference-jti")
                .claim("scope", "model-registry.inference.resolve")
                .claim("actor_user_id", ACTOR_ID)
                .build();
    }
}
