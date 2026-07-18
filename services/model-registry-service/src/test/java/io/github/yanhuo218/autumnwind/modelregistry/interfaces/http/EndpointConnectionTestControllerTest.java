package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.EndpointConnectionTestService;
import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.EndpointConnectionTestStatus;
import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.EndpointConnectionTestView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EndpointConnectionTestController.class)
class EndpointConnectionTestControllerTest {

    private static final UUID ACTOR_ID = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
    private static final UUID ENDPOINT_ID = UUID.fromString("2d3b1f8a-0ed4-4c3e-a2ab-d1a7580c2201");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointConnectionTestService service;

    @Test
    void 创建连接测试任务返回202且不暴露凭据引用() throws Exception {
        UUID jobId = UUID.fromString("b88e1f00-83dc-4cf0-a7b3-000000000002");
        when(service.enqueue(any())).thenAnswer(invocation -> {
            var command = (io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.EndpointConnectionTestCommand)
                    invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals(ACTOR_ID, command.ownerUserId());
            org.junit.jupiter.api.Assertions.assertEquals(ENDPOINT_ID, command.endpointId());
            org.junit.jupiter.api.Assertions.assertEquals(0, command.expectedVersion());
            return new EndpointConnectionTestView(jobId, ENDPOINT_ID, EndpointConnectionTestStatus.QUEUED,
                    0, Instant.parse("2026-07-18T00:00:00Z"));
        });

        mockMvc.perform(post("/api/v1/model-registry/endpoints/" + ENDPOINT_ID + "/connection-tests")
                        .principal(servicePrincipal())
                        .header("X-Correlation-ID", "01JZ8M4A7X4S6NR2YQF1D9K3CP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(content().string(not(containsString("credential"))));
    }

    private static JwtAuthenticationToken servicePrincipal() {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        Jwt jwt = new Jwt("test-service-token", now.minusSeconds(30), now.plusSeconds(240),
                Map.of("alg", "RS256"), Map.of("sub", "gateway-service",
                        "scope", "model-registry.endpoint.manage", "actor_user_id", ACTOR_ID.toString(),
                        "aud", List.of("model-registry-service")));
        return new JwtAuthenticationToken(jwt);
    }
}
