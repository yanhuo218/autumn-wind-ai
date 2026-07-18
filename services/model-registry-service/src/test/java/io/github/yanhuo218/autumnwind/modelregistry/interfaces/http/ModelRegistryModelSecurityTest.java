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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        "autumn-wind.model-registry.service-jwt.maximum-lifetime=PT5M"
})
class ModelRegistryModelSecurityTest {

    private static final String MODELS_PATH = "/api/v1/model-registry/models";
    private static final String ACTOR_ID = "f7590cc5-1e56-4a28-ac97-e58380a6d94e";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModelAdministrationService administrationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void 端点Scope不能访问模型接口() throws Exception {
        mockMvc.perform(get(MODELS_PATH)
                        .with(jwt()
                                .jwt(jwt -> jwt.claim("actor_user_id", ACTOR_ID))
                                .authorities(new SimpleGrantedAuthority("SCOPE_model-registry.endpoint.manage"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-MODEL-REGISTRY-FORBIDDEN-0001"));
    }

    @Test
    void 模型Scope和规范操作者可以读取自己的模型() throws Exception {
        when(administrationService.list(java.util.UUID.fromString(ACTOR_ID))).thenReturn(List.of());

        mockMvc.perform(get(MODELS_PATH)
                        .with(jwt()
                                .jwt(jwt -> jwt.subject("gateway-service")
                                        .claim("scope", "model-registry.model.manage")
                                        .claim("actor_user_id", ACTOR_ID))
                                .authorities(new SimpleGrantedAuthority("SCOPE_model-registry.model.manage"))))
                .andExpect(status().isOk());
    }
}
