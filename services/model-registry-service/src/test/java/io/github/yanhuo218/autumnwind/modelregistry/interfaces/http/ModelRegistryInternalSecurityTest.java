package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.inference.InferenceTargetResolutionService;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.configuration.ModelRegistrySecurityConfiguration;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.security.ModelRegistrySecurityErrorWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        "autumn-wind.model-registry.service-jwt.maximum-lifetime=PT5M"
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

    @MockitoBean
    private JwtDecoder jwtDecoder;

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
}
