package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.model.ModelAdministrationService;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.configuration.ModelRegistrySecurityConfiguration;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.security.ModelRegistrySecurityErrorWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ModelAdministrationController.class)
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
class ModelRegistryModelSecurityTest {

    private static final String MODELS_PATH = "/api/v1/model-registry/models";
    private static final String ACTOR_ID = "f7590cc5-1e56-4a28-ac97-e58380a6d94e";
    private static final String MODEL_ID = "0f7590cc-1e56-4a28-ac97-e58380a6d94e";
    private static final String MODEL_CREATE_REQUEST_BODY = """
            {
              "endpointId": "2d3b1f8a-0ed4-4c3e-a2ab-d1a7580c2201",
              "providerModelId": "vision-model",
              "displayName": "视觉模型",
              "capabilities": {
                "interfaceType": "CHAT_COMPLETIONS",
                "inputModalities": ["TEXT"],
                "outputModality": "TEXT",
                "streaming": true,
                "systemPrompt": true,
                "reasoning": false,
                "contextLength": 8192,
                "maxOutputLength": 2048
              },
              "enabled": true,
              "defaultModel": false
            }
            """;
    private static final String MODEL_UPDATE_REQUEST_BODY = """
            {
              "providerModelId": "vision-model",
              "displayName": "视觉模型",
              "capabilities": {
                "interfaceType": "CHAT_COMPLETIONS",
                "inputModalities": ["TEXT"],
                "outputModality": "TEXT",
                "streaming": true,
                "systemPrompt": true,
                "reasoning": false,
                "contextLength": 8192,
                "maxOutputLength": 2048
              },
              "enabled": true,
              "defaultModel": false,
              "expectedVersion": 0
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModelAdministrationService administrationService;

    @MockitoBean(name = "inferenceJwtDecoder")
    private JwtDecoder inferenceJwtDecoder;

    @MockitoBean(name = "modelRegistryServiceJwtDecoder")
    private JwtDecoder modelRegistryServiceJwtDecoder;

    @Test
    void 网关令牌仅由公共链验证并且内部链拒绝() throws Exception {
        when(administrationService.list(java.util.UUID.fromString(ACTOR_ID))).thenReturn(List.of());
        when(modelRegistryServiceJwtDecoder.decode("gateway-token")).thenReturn(gatewayJwt());
        when(inferenceJwtDecoder.decode("gateway-token"))
                .thenThrow(new BadJwtException("内部信任域不接受网关令牌。"));

        mockMvc.perform(get(MODELS_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer gateway-token"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/internal/v1/model-registry/inference-target-resolutions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer gateway-token")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-AUTH-0001"));
    }

    @Test
    void 端点Scope不能访问模型接口() throws Exception {
        mockMvc.perform(get(MODELS_PATH)
                        .with(jwt()
                                .jwt(jwt -> jwt.claim("actor_user_id", ACTOR_ID))
                                .authorities(new SimpleGrantedAuthority("SCOPE_model-registry.endpoint.manage"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-FORBIDDEN-0001"));
    }

    @Test
    void 只读模型Scope和规范操作者可以读取模型列表和详情() throws Exception {
        when(administrationService.list(java.util.UUID.fromString(ACTOR_ID))).thenReturn(List.of());

        mockMvc.perform(get(MODELS_PATH)
                        .with(modelScopeToken("model-registry.model.read", ACTOR_ID)))
                .andExpect(status().isOk());

        mockMvc.perform(get(MODELS_PATH + "/" + MODEL_ID)
                        .with(modelScopeToken("model-registry.model.read", ACTOR_ID)))
                .andExpect(status().isOk());
    }

    @Test
    void 只读模型Scope不能创建或更新模型() throws Exception {
        mockMvc.perform(post(MODELS_PATH)
                        .with(modelScopeToken("model-registry.model.read", ACTOR_ID))
                        .contentType("application/json")
                        .content("{"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put(MODELS_PATH + "/" + MODEL_ID)
                        .with(modelScopeToken("model-registry.model.read", ACTOR_ID))
                        .contentType("application/json")
                        .content("{"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 模型管理Scope和规范操作者仍可读取创建和更新模型() throws Exception {
        when(administrationService.list(java.util.UUID.fromString(ACTOR_ID))).thenReturn(List.of());

        mockMvc.perform(get(MODELS_PATH)
                        .with(modelScopeToken("model-registry.model.manage", ACTOR_ID)))
                .andExpect(status().isOk());

        mockMvc.perform(post(MODELS_PATH)
                        .with(modelScopeToken("model-registry.model.manage", ACTOR_ID))
                        .contentType("application/json")
                        .content(MODEL_CREATE_REQUEST_BODY))
                .andExpect(status().isCreated());

        mockMvc.perform(put(MODELS_PATH + "/" + MODEL_ID)
                        .with(modelScopeToken("model-registry.model.manage", ACTOR_ID))
                        .contentType("application/json")
                        .content(MODEL_UPDATE_REQUEST_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void 模型读取和管理Scope均拒绝缺失非法或非规范操作者() throws Exception {
        for (String scope : List.of("model-registry.model.read", "model-registry.model.manage")) {
            for (String actorUserId : new String[]{null, "not-a-uuid", "{" + ACTOR_ID + "}"}) {
                mockMvc.perform(get(MODELS_PATH)
                                .with(modelScopeToken(scope, actorUserId)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-FORBIDDEN-0001"));
            }
        }
    }

    private static RequestPostProcessor modelScopeToken(String scope, String actorUserId) {
        return jwt()
                .jwt(jwt -> {
                    jwt.subject("gateway-service").claim("scope", scope);
                    if (actorUserId != null) {
                        jwt.claim("actor_user_id", actorUserId);
                    }
                })
                .authorities(new SimpleGrantedAuthority("SCOPE_" + scope));
    }

    private static Jwt gatewayJwt() {
        java.time.Instant issuedAt = java.time.Instant.now();
        return Jwt.withTokenValue("gateway-token")
                .header("alg", "RS256")
                .issuer("https://issuer.example")
                .subject("gateway-service")
                .audience(List.of("model-registry-service"))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(300))
                .claim("jti", "gateway-jti")
                .claim("scope", "model-registry.model.read")
                .claim("actor_user_id", ACTOR_ID)
                .build();
    }
}
