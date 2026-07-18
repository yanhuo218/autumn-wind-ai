package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.EndpointAdministrationService;
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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EndpointAdministrationController.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({ModelRegistrySecurityConfiguration.class, ModelRegistrySecurityErrorWriter.class, CorrelationIdFilter.class})
@TestPropertySource(properties = {
        "autumn-wind.model-registry.service-jwt.issuer=https://issuer.example",
        "autumn-wind.model-registry.service-jwt.audience=model-registry-service",
        "autumn-wind.model-registry.service-jwt.jwk-set-uri=https://issuer.example/.well-known/jwks.json",
        "autumn-wind.model-registry.service-jwt.allowed-callers=gateway-service,admin-service",
        "autumn-wind.model-registry.service-jwt.maximum-lifetime=PT5M"
})
class ModelRegistrySecurityTest {

    private static final String ENDPOINTS_PATH = "/api/v1/model-registry/endpoints";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointAdministrationService administrationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void 缺少ServiceJwt返回统一Bearer401() throws Exception {
        mockMvc.perform(get(ENDPOINTS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AW-MODEL-REGISTRY-AUTH-0001"));
    }

    @Test
    void 缺少专用Scope或操作者声明返回403() throws Exception {
        mockMvc.perform(get(ENDPOINTS_PATH)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_other"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-MODEL-REGISTRY-FORBIDDEN-0001"));

        mockMvc.perform(get(ENDPOINTS_PATH)
                        .with(jwt()
                                .jwt(jwt -> jwt.subject("gateway-service")
                                        .claim("scope", "model-registry.endpoint.manage"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_model-registry.endpoint.manage"))))
                .andExpect(status().isForbidden());

        verify(administrationService, never()).list(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 未知管理路径返回403() throws Exception {
        mockMvc.perform(get("/api/v1/model-registry/unknown")
                        .with(jwt()
                                .jwt(jwt -> jwt.subject("gateway-service")
                                        .claim("scope", "model-registry.endpoint.manage")
                                        .claim("actor_user_id", "f7590cc5-1e56-4a28-ac97-e58380a6d94e"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_model-registry.endpoint.manage"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-MODEL-REGISTRY-FORBIDDEN-0001"));
    }
}
