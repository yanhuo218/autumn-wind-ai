package io.github.yanhuo218.autumnwind.modelregistry.application.model;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ModelRegistryApplicationException;
import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ModelRegistryErrorCode;
import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointProtocol;
import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointSettings;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.InputModality;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelCapabilities;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelInterfaceType;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.OutputModality;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointRepository;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.ModelEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.ModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelAdministrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENDPOINT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MODEL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private ModelRepository modelRepository;

    private ModelAdministrationService service;

    @BeforeEach
    void setUp() {
        service = new ModelAdministrationService(
                endpointRepository,
                modelRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void 在所属端点下创建模型和能力() {
        when(endpointRepository.findByIdAndOwnerUserId(ENDPOINT_ID, OWNER_ID)).thenReturn(Optional.of(endpoint()));
        when(modelRepository.existsByOwnerUserIdAndEndpointIdAndProviderModelIdAndIdNot(
                any(), any(), any(), any()
        )).thenReturn(false);
        when(modelRepository.existsByOwnerUserIdAndInterfaceTypeAndDefaultModelTrueAndIdNot(
                any(), any(), any()
        )).thenReturn(false);
        when(modelRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ModelView view = service.create(createCommand(true));

        assertEquals(OWNER_ID, view.ownerUserId());
        assertEquals(ENDPOINT_ID, view.endpointId());
        assertEquals("provider-model", view.providerModelId());
        assertEquals(ModelInterfaceType.CHAT_COMPLETIONS, view.capabilities().interfaceType());
        assertTrue(view.defaultModel());
        assertEquals(1, view.capabilitySchemaVersion());
    }

    @Test
    void 端点不属于当前用户时返回端点不存在() {
        when(endpointRepository.findByIdAndOwnerUserId(ENDPOINT_ID, OWNER_ID)).thenReturn(Optional.empty());

        ModelRegistryApplicationException exception = assertThrows(
                ModelRegistryApplicationException.class,
                () -> service.create(createCommand(false))
        );

        assertEquals(ModelRegistryErrorCode.ENDPOINT_NOT_FOUND, exception.code());
        verifyNoInteractions(modelRepository);
    }

    @Test
    void 拒绝同一端点重复服务商模型标识() {
        when(endpointRepository.findByIdAndOwnerUserId(ENDPOINT_ID, OWNER_ID)).thenReturn(Optional.of(endpoint()));
        when(modelRepository.existsByOwnerUserIdAndEndpointIdAndProviderModelIdAndIdNot(
                any(), any(), any(), any()
        )).thenReturn(true);

        ModelRegistryApplicationException exception = assertThrows(
                ModelRegistryApplicationException.class,
                () -> service.create(createCommand(false))
        );

        assertEquals(ModelRegistryErrorCode.MODEL_CONFLICT, exception.code());
        verify(modelRepository, never()).saveAndFlush(any());
    }

    @Test
    void 拒绝同一接口类型存在两个默认模型() {
        when(endpointRepository.findByIdAndOwnerUserId(ENDPOINT_ID, OWNER_ID)).thenReturn(Optional.of(endpoint()));
        when(modelRepository.existsByOwnerUserIdAndEndpointIdAndProviderModelIdAndIdNot(
                any(), any(), any(), any()
        )).thenReturn(false);
        when(modelRepository.existsByOwnerUserIdAndInterfaceTypeAndDefaultModelTrueAndIdNot(
                any(), any(), any()
        )).thenReturn(true);

        ModelRegistryApplicationException exception = assertThrows(
                ModelRegistryApplicationException.class,
                () -> service.create(createCommand(true))
        );

        assertEquals(ModelRegistryErrorCode.DEFAULT_MODEL_CONFLICT, exception.code());
        verify(modelRepository, never()).saveAndFlush(any());
    }

    @Test
    void 读取模型始终绑定所有者() {
        when(modelRepository.findByIdAndOwnerUserId(MODEL_ID, OWNER_ID)).thenReturn(Optional.of(model()));

        ModelView view = service.get(OWNER_ID, MODEL_ID);

        assertEquals(MODEL_ID, view.id());
        assertEquals(OWNER_ID, view.ownerUserId());
    }

    @Test
    void 列出模型时按所有者映射能力视图() {
        when(modelRepository.findAllByOwnerUserIdOrderByCreatedAtAsc(OWNER_ID)).thenReturn(List.of(model()));

        List<ModelView> views = service.list(OWNER_ID);

        assertEquals(1, views.size());
        assertEquals(OWNER_ID, views.getFirst().ownerUserId());
        assertEquals(ModelInterfaceType.CHAT_COMPLETIONS, views.getFirst().capabilities().interfaceType());
    }

    @Test
    void 更新版本不匹配时不写库() {
        when(modelRepository.findByIdAndOwnerUserId(MODEL_ID, OWNER_ID)).thenReturn(Optional.of(model()));

        ModelRegistryApplicationException exception = assertThrows(
                ModelRegistryApplicationException.class,
                () -> service.update(updateCommand(1))
        );

        assertEquals(ModelRegistryErrorCode.MODEL_VERSION_CONFLICT, exception.code());
        verify(modelRepository, never()).saveAndFlush(any());
    }

    @Test
    void 更新模型元数据和能力() {
        ModelEntity model = model();
        when(modelRepository.findByIdAndOwnerUserId(MODEL_ID, OWNER_ID)).thenReturn(Optional.of(model));
        when(modelRepository.existsByOwnerUserIdAndEndpointIdAndProviderModelIdAndIdNot(
                any(), any(), any(), any()
        )).thenReturn(false);
        when(modelRepository.saveAndFlush(model)).thenReturn(model);

        ModelView view = service.update(updateCommand(0));

        assertEquals("updated-model", view.providerModelId());
        assertEquals("更新模型", view.displayName());
        assertFalse(view.enabled());
        assertFalse(view.capabilities().streaming());
        assertEquals(4_096, view.capabilities().contextLength());
    }

    private static CreateModelCommand createCommand(boolean defaultModel) {
        return new CreateModelCommand(
                OWNER_ID,
                ENDPOINT_ID,
                "provider-model",
                "主要模型",
                capabilities(true, 8_192),
                true,
                defaultModel
        );
    }

    private static UpdateModelCommand updateCommand(long expectedVersion) {
        return new UpdateModelCommand(
                OWNER_ID,
                MODEL_ID,
                "updated-model",
                "更新模型",
                capabilities(false, 4_096),
                false,
                false,
                expectedVersion
        );
    }

    private static ModelEntity model() {
        return ModelEntity.create(
                MODEL_ID,
                endpoint(),
                "provider-model",
                "主要模型",
                capabilities(true, 8_192),
                true,
                false,
                NOW
        );
    }

    private static EndpointEntity endpoint() {
        return EndpointEntity.create(ENDPOINT_ID, OWNER_ID, endpointSettings(), NOW);
    }

    private static EndpointSettings endpointSettings() {
        return new EndpointSettings(
                "主要端点",
                URI.create("https://api.example.com/v1"),
                EndpointProtocol.OPENAI_COMPATIBLE,
                Duration.ofSeconds(30),
                true
        );
    }

    private static ModelCapabilities capabilities(boolean streaming, int contextLength) {
        return new ModelCapabilities(
                ModelInterfaceType.CHAT_COMPLETIONS,
                Set.of(InputModality.TEXT),
                OutputModality.TEXT,
                streaming,
                true,
                false,
                contextLength,
                1_024
        );
    }
}
