package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ConnectionTestWorkerService;
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

import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ConnectionTestWorkerController.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({ModelRegistrySecurityConfiguration.class, ModelRegistrySecurityErrorWriter.class, CorrelationIdFilter.class})
@TestPropertySource(properties = {
        "autumn-wind.model-registry.service-jwt.issuer=https://issuer.example",
        "autumn-wind.model-registry.service-jwt.audience=model-registry-service",
        "autumn-wind.model-registry.service-jwt.jwk-set-uri=https://issuer.example/.well-known/jwks.json",
        "autumn-wind.model-registry.service-jwt.allowed-callers=gateway-service,admin-service",
        "autumn-wind.model-registry.service-jwt.maximum-lifetime=PT5M"
})
class ConnectionTestWorkerControllerSecurityTest {

    private static final String CLAIM_PATH = "/internal/v1/model-registry/connection-test-jobs/claims";
    private static final String ACTOR_ID = "f7590cc5-1e56-4a28-ac97-e58380a6d94e";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConnectionTestWorkerService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void 缺少ServiceJwt时返回401() throws Exception {
        mockMvc.perform(post(CLAIM_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-AUTH-0001"));

        verify(service, never()).claim();
    }

    @Test
    void 缺少连接测试执行Scope时返回403() throws Exception {
        mockMvc.perform(post(CLAIM_PATH)
                        .with(jwt()
                                .jwt(jwt -> jwt.claim("actor_user_id", ACTOR_ID))
                                .authorities(new SimpleGrantedAuthority("SCOPE_model-registry.inference.resolve"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-FORBIDDEN-0001"));

        verify(service, never()).claim();
    }

    @Test
    void 正确Scope领取不到任务时返回204() throws Exception {
        when(service.claim()).thenReturn(Optional.empty());

        mockMvc.perform(post(CLAIM_PATH)
                        .with(jwt()
                                .jwt(jwt -> jwt.claim("actor_user_id", ACTOR_ID))
                                .authorities(new SimpleGrantedAuthority(
                                        "SCOPE_model-registry.connection-test.execute"))))
                .andExpect(status().isNoContent());

        verify(service).claim();
    }

    @Test
    void 后台Worker只有执行Scope且没有用户操作者声明时也可领取() throws Exception {
        when(service.claim()).thenReturn(Optional.empty());

        mockMvc.perform(post(CLAIM_PATH)
                        .with(jwt().authorities(new SimpleGrantedAuthority(
                                "SCOPE_model-registry.connection-test.execute"))))
                .andExpect(status().isNoContent());

        verify(service).claim();
    }

    @Test
    void 四个内部操作都要求相同专用Scope() throws Exception {
        String body = """
                {
                  "jobId":"11111111-1111-1111-1111-111111111111",
                  "leaseId":"88888888-8888-8888-8888-888888888888",
                  "jobVersion":0
                }
                """;

        for (String path : new String[]{
                "/internal/v1/model-registry/connection-test-jobs/lease-renewals",
                "/internal/v1/model-registry/connection-test-jobs/successes",
                "/internal/v1/model-registry/connection-test-jobs/failures"
        }) {
            mockMvc.perform(post(path)
                            .with(jwt()
                                    .jwt(jwt -> jwt.claim("actor_user_id", ACTOR_ID))
                                    .authorities(new SimpleGrantedAuthority(
                                            "SCOPE_model-registry.inference.resolve")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void 三个写操作都拒绝缺失或null版本以及未知字段() throws Exception {
        for (String path : new String[]{
                "/internal/v1/model-registry/connection-test-jobs/lease-renewals",
                "/internal/v1/model-registry/connection-test-jobs/successes",
                "/internal/v1/model-registry/connection-test-jobs/failures"
        }) {
            assertBadRequest(path, requestBody(path, ""));
            assertBadRequest(path, requestBody(path, ",\"jobVersion\":null"));
            assertBadRequest(path, requestBody(path, ",\"jobVersion\":0,\"unexpected\":true"));
        }

        verifyNoInteractions(service);
    }

    private void assertBadRequest(String path, String body) throws Exception {
        mockMvc.perform(post(path)
                        .with(jwt().authorities(new SimpleGrantedAuthority(
                                "SCOPE_model-registry.connection-test.execute")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-VALIDATION-0001"));
    }

    private static String requestBody(String path, String versionFields) {
        String failureField = path.endsWith("/failures")
                ? ",\"failureCode\":\"PROVIDER_ERROR\""
                : "";
        return "{\"jobId\":\"11111111-1111-1111-1111-111111111111\""
                + ",\"leaseId\":\"88888888-8888-8888-8888-888888888888\""
                + failureField + versionFields + "}";
    }
}
