package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.inference.EncryptedCredentialEnvelope;
import io.github.yanhuo218.autumnwind.modelregistry.application.inference.InferenceTargetResolutionService;
import io.github.yanhuo218.autumnwind.modelregistry.application.inference.InferenceTargetView;
import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointProtocol;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.InputModality;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelCapabilities;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelInterfaceType;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.OutputModality;
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
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InferenceTargetResolutionController.class)
class InferenceTargetResolutionControllerTest {

    private static final UUID ACTOR_ID = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
    private static final UUID MODEL_ID = UUID.fromString("b88e1f00-83dc-4cf0-a7b3-000000000001");
    private static final UUID ENDPOINT_ID = UUID.fromString("2d3b1f8a-0ed4-4c3e-a2ab-d1a7580c2201");
    private static final UUID CREDENTIAL_ID = UUID.fromString("a24a3063-1e16-49dd-b1a8-6edb9d477810");
    private static final String PATH = "/internal/v1/model-registry/inference-target-resolutions";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InferenceTargetResolutionService resolutionService;

    @Test
    void 返回加密调用快照并禁止缓存() throws Exception {
        when(resolutionService.resolve(ACTOR_ID, MODEL_ID)).thenReturn(view());

        mockMvc.perform(post(PATH)
                        .principal(servicePrincipal(ACTOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(ACTOR_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.modelId").value(MODEL_ID.toString()))
                .andExpect(jsonPath("$.credential.keyId").value("local-v1"))
                .andExpect(content().string(not(containsString("apiKey"))));

        verify(resolutionService).resolve(ACTOR_ID, MODEL_ID);
    }

    @Test
    void 未知字段返回400且不调用应用服务() throws Exception {
        mockMvc.perform(post(PATH)
                        .principal(servicePrincipal(ACTOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(ACTOR_ID).replace("}", ",\"unknown\":true}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-VALIDATION-0001"));

        verify(resolutionService, never()).resolve(any(), any());
    }

    @Test
    void 租户声明与请求所有者不一致返回403() throws Exception {
        UUID anotherOwner = UUID.fromString("c92b1cbe-b948-4eb1-af8f-4d3cfd9306b5");

        mockMvc.perform(post(PATH)
                        .principal(servicePrincipal(ACTOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(anotherOwner)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-FORBIDDEN-0001"));

        verify(resolutionService, never()).resolve(any(), any());
    }

    private static String requestBody(UUID ownerUserId) {
        return "{\"ownerUserId\":\"" + ownerUserId + "\",\"modelId\":\"" + MODEL_ID + "\"}";
    }

    private static JwtAuthenticationToken servicePrincipal(UUID actorUserId) {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        Jwt jwt = new Jwt("test-service-token", now.minusSeconds(30), now.plusSeconds(240),
                Map.of("alg", "RS256"), Map.of("sub", "gateway-service",
                        "scope", "model-registry.inference.resolve",
                        "actor_user_id", actorUserId.toString(), "aud", List.of("model-registry-service")));
        return new JwtAuthenticationToken(jwt);
    }

    private static InferenceTargetView view() {
        return new InferenceTargetView(
                MODEL_ID,
                "provider-chat",
                0,
                ENDPOINT_ID,
                URI.create("https://api.example.com/v1"),
                EndpointProtocol.OPENAI_COMPATIBLE,
                30,
                0,
                new ModelCapabilities(ModelInterfaceType.CHAT_COMPLETIONS, Set.of(InputModality.TEXT),
                        OutputModality.TEXT, true, true, false, 8_192, 1_024),
                CREDENTIAL_ID,
                new EncryptedCredentialEnvelope(1, "local-v1", "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAA")
        );
    }
}
