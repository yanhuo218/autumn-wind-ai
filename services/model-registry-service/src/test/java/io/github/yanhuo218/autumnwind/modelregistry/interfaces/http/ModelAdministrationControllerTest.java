package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.model.ModelAdministrationService;
import io.github.yanhuo218.autumnwind.modelregistry.application.model.ModelView;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ModelAdministrationController.class)
class ModelAdministrationControllerTest {

    private static final UUID ACTOR_ID = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
    private static final UUID ENDPOINT_ID = UUID.fromString("2d3b1f8a-0ed4-4c3e-a2ab-d1a7580c2201");
    private static final UUID MODEL_ID = UUID.fromString("b88e1f00-83dc-4cf0-a7b3-000000000001");
    private static final String MODELS_PATH = "/api/v1/model-registry/models";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModelAdministrationService administrationService;

    @Test
    void 查询模型列表返回能力且不泄露所有者输入() throws Exception {
        when(administrationService.list(ACTOR_ID)).thenReturn(List.of(view()));

        mockMvc.perform(get(MODELS_PATH).principal(servicePrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(MODEL_ID.toString()))
                .andExpect(jsonPath("$[0].capabilities.interfaceType").value("CHAT_COMPLETIONS"))
                .andExpect(jsonPath("$[0].capabilities.inputModalities")
                        .value(containsInAnyOrder("TEXT", "IMAGE")));
    }

    @Test
    void 创建模型从Jwt取得所有者并发送能力字段() throws Exception {
        when(administrationService.create(any())).thenAnswer(invocation -> {
            var command = (io.github.yanhuo218.autumnwind.modelregistry.application.model.CreateModelCommand)
                    invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals(ACTOR_ID, command.ownerUserId());
            org.junit.jupiter.api.Assertions.assertEquals(ENDPOINT_ID, command.endpointId());
            org.junit.jupiter.api.Assertions.assertEquals(InputModality.IMAGE, command.capabilities().inputModalities().stream()
                    .filter(modality -> modality == InputModality.IMAGE).findFirst().orElseThrow());
            return view();
        });

        mockMvc.perform(post(MODELS_PATH)
                        .principal(servicePrincipal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(MODEL_ID.toString()))
                .andExpect(jsonPath("$.capabilities.contextLength").value(8192));
    }

    @Test
    void 更新模型使用路径模型标识和版本() throws Exception {
        when(administrationService.update(any())).thenAnswer(invocation -> {
            var command = (io.github.yanhuo218.autumnwind.modelregistry.application.model.UpdateModelCommand)
                    invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals(ACTOR_ID, command.ownerUserId());
            org.junit.jupiter.api.Assertions.assertEquals(MODEL_ID, command.modelId());
            org.junit.jupiter.api.Assertions.assertEquals(4, command.expectedVersion());
            return view();
        });

        mockMvc.perform(put(MODELS_PATH + "/" + MODEL_ID)
                        .principal(servicePrincipal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    void 非法能力组合返回400且不调用应用服务() throws Exception {
        mockMvc.perform(post(MODELS_PATH)
                        .principal(servicePrincipal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody().replace("CHAT_COMPLETIONS", "IMAGE_GENERATION")
                                .replace("\"outputModality\":\"TEXT\"", "\"outputModality\":\"IMAGE\"")
                                .replace("\"streaming\":true", "\"streaming\":false")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-VALIDATION-0001"));

        verify(administrationService, never()).create(any());
    }

    @Test
    void 缺失能力必填字段返回400而不是500() throws Exception {
        mockMvc.perform(post(MODELS_PATH)
                        .principal(servicePrincipal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody().replace("\"outputModality\":\"TEXT\",", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AW-MODEL_REGISTRY-VALIDATION-0001"));

        verify(administrationService, never()).create(any());
    }

    private static String createBody() {
        return """
                {
                  "endpointId":"2d3b1f8a-0ed4-4c3e-a2ab-d1a7580c2201",
                  "providerModelId":"vision-model",
                  "displayName":"视觉模型",
                  "capabilities":{
                    "interfaceType":"CHAT_COMPLETIONS",
                    "inputModalities":["TEXT","IMAGE"],
                    "outputModality":"TEXT",
                    "streaming":true,
                    "systemPrompt":true,
                    "reasoning":false,
                    "contextLength":8192,
                    "maxOutputLength":2048
                  },
                  "enabled":true,
                  "defaultModel":false
                }
                """;
    }

    private static String updateBody() {
        return """
                {
                  "providerModelId":"vision-model",
                  "displayName":"视觉模型",
                  "capabilities":{
                    "interfaceType":"CHAT_COMPLETIONS",
                    "inputModalities":["TEXT","IMAGE"],
                    "outputModality":"TEXT",
                    "streaming":true,
                    "systemPrompt":true,
                    "reasoning":false,
                    "contextLength":8192,
                    "maxOutputLength":2048
                  },
                  "enabled":true,
                  "defaultModel":false,
                  "expectedVersion":4
                }
                """;
    }

    private static JwtAuthenticationToken servicePrincipal() {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        Jwt jwt = new Jwt("test-service-token", now.minusSeconds(30), now.plusSeconds(240),
                Map.of("alg", "RS256"), Map.of("sub", "gateway-service",
                        "scope", "model-registry.model.manage",
                        "actor_user_id", ACTOR_ID.toString(), "aud", List.of("model-registry-service")));
        return new JwtAuthenticationToken(jwt);
    }

    private static ModelView view() {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        return new ModelView(MODEL_ID, ACTOR_ID, ENDPOINT_ID, "vision-model", "视觉模型",
                new ModelCapabilities(ModelInterfaceType.CHAT_COMPLETIONS,
                        Set.of(InputModality.TEXT, InputModality.IMAGE), OutputModality.TEXT,
                        true, true, false, 8192, 2048), true, false, 1, 4, now, now);
    }
}
