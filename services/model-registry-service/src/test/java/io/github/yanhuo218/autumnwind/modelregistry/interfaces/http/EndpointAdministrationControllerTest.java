package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.EndpointAdministrationService;
import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.EndpointView;
import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EndpointAdministrationController.class)
class EndpointAdministrationControllerTest {

    private static final UUID ACTOR_ID = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
    private static final UUID ENDPOINT_ID = UUID.fromString("2d3b1f8a-0ed4-4c3e-a2ab-d1a7580c2201");
    private static final String ENDPOINTS_PATH = "/api/v1/model-registry/endpoints";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointAdministrationService administrationService;

    @Test
    void 查询端点列表只返回当前用户的公开字段() throws Exception {
        when(administrationService.list(ACTOR_ID)).thenReturn(List.of(view()));

        mockMvc.perform(get(ENDPOINTS_PATH)
                        .principal(servicePrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ENDPOINT_ID.toString()))
                .andExpect(jsonPath("$[0].credentialConfigured").value(true))
                .andExpect(content().string(not(containsString("api-key"))));
    }

    @Test
    void 创建端点从Jwt取得所有者且不回显ApiKey() throws Exception {
        when(administrationService.create(any())).thenAnswer(invocation -> {
            var command = (io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.CreateEndpointCommand)
                    invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals(ACTOR_ID, command.ownerUserId());
            org.junit.jupiter.api.Assertions.assertEquals("temporary-api-key", command.apiKey());
            return view();
        });

        mockMvc.perform(post(ENDPOINTS_PATH)
                        .principal(servicePrincipal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName":"主要端点",
                                  "baseUrl":"https://api.example.com/v1",
                                  "protocol":"OPENAI_COMPATIBLE",
                                  "requestTimeoutSeconds":30,
                                  "enabled":true,
                                  "apiKey":"temporary-api-key"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ENDPOINT_ID.toString()))
                .andExpect(content().string(not(containsString("temporary-api-key"))))
                .andExpect(content().string(not(containsString("apiKey"))));
    }

    @Test
    void 替换凭据使用路径端点和Jwt所有者() throws Exception {
        when(administrationService.replaceKey(any())).thenAnswer(invocation -> {
            var command = (io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ReplaceEndpointKeyCommand)
                    invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals(ACTOR_ID, command.ownerUserId());
            org.junit.jupiter.api.Assertions.assertEquals(ENDPOINT_ID, command.endpointId());
            org.junit.jupiter.api.Assertions.assertEquals(3, command.expectedVersion());
            return view();
        });

        mockMvc.perform(put(ENDPOINTS_PATH + "/" + ENDPOINT_ID + "/credential")
                        .principal(servicePrincipal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"apiKey":"temporary-api-key","expectedVersion":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialConfigured").value(true))
                .andExpect(content().string(not(containsString("temporary-api-key"))));
    }

    @Test
    void 非法请求不会调用应用服务() throws Exception {
        mockMvc.perform(post(ENDPOINTS_PATH)
                        .principal(servicePrincipal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"","baseUrl":"http://not-https","apiKey":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-VALIDATION-0001"));

        verify(administrationService, never()).create(any());
    }

    @Test
    void 关联标识保留在成功和错误响应() throws Exception {
        String correlationId = "01JZ8M4A7X4S6NR2YQF1D9K3CP";
        when(administrationService.list(ACTOR_ID)).thenReturn(List.of());

        mockMvc.perform(get(ENDPOINTS_PATH)
                        .principal(servicePrincipal())
                        .header("X-Correlation-ID", correlationId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", correlationId));
    }

    @Test
    void 非法端点路径标识返回400() throws Exception {
        mockMvc.perform(get(ENDPOINTS_PATH + "/not-a-uuid")
                        .principal(servicePrincipal()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-VALIDATION-0001"));
    }

    private static JwtAuthenticationToken servicePrincipal() {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        Jwt jwt = new Jwt(
                "test-service-token",
                now.minusSeconds(30),
                now.plusSeconds(240),
                Map.of("alg", "RS256"),
                Map.of("sub", "gateway-service", "scope", "model-registry.endpoint.manage",
                        "actor_user_id", ACTOR_ID.toString(), "aud", List.of("model-registry-service"))
        );
        return new JwtAuthenticationToken(jwt);
    }

    private static EndpointView view() {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        return new EndpointView(
                ENDPOINT_ID,
                ACTOR_ID,
                "主要端点",
                URI.create("https://api.example.com/v1"),
                EndpointProtocol.OPENAI_COMPATIBLE,
                30,
                true,
                true,
                3,
                now,
                now
        );
    }
}
