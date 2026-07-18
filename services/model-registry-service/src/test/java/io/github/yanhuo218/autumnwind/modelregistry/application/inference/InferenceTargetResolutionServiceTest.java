package io.github.yanhuo218.autumnwind.modelregistry.application.inference;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ModelRegistryApplicationException;
import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ModelRegistryErrorCode;
import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointProtocol;
import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointSettings;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.InputModality;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelCapabilities;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelInterfaceType;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.OutputModality;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointCredentialEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointRepository;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.ModelEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.ModelRepository;
import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InferenceTargetResolutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENDPOINT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MODEL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CREDENTIAL_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    private ModelRepository modelRepository;

    @Mock
    private EndpointRepository endpointRepository;

    private InferenceTargetResolutionService service;

    @BeforeEach
    void setUp() {
        service = new InferenceTargetResolutionService(modelRepository, endpointRepository);
    }

    @Test
    void 解析启用的聊天模型为固定加密调用快照() {
        ModelEntity model = model(true, ModelInterfaceType.CHAT_COMPLETIONS);
        EndpointEntity endpoint = endpoint(true, true);
        when(modelRepository.findByIdAndOwnerUserId(MODEL_ID, OWNER_ID)).thenReturn(Optional.of(model));
        when(endpointRepository.findByIdAndOwnerUserId(ENDPOINT_ID, OWNER_ID)).thenReturn(Optional.of(endpoint));

        InferenceTargetView view = service.resolve(OWNER_ID, MODEL_ID);

        assertEquals(MODEL_ID, view.modelId());
        assertEquals("provider-chat", view.providerModelId());
        assertEquals(ENDPOINT_ID, view.endpointId());
        assertEquals(0, view.modelVersion());
        assertEquals(0, view.endpointVersion());
        assertEquals(CREDENTIAL_ID, view.credentialId());
        assertEquals("local-v1", view.credential().keyId());
        assertEquals("EncryptedCredentialEnvelope[version=1, keyId=<REDACTED>]", view.credential().toString());
    }

    @Test
    void 未知租户模型返回模型不存在() {
        when(modelRepository.findByIdAndOwnerUserId(MODEL_ID, OWNER_ID)).thenReturn(Optional.empty());

        ModelRegistryApplicationException exception = assertThrows(
                ModelRegistryApplicationException.class,
                () -> service.resolve(OWNER_ID, MODEL_ID)
        );

        assertEquals(ModelRegistryErrorCode.MODEL_NOT_FOUND, exception.code());
        verifyNoInteractions(endpointRepository);
    }

    @Test
    void 禁用模型不可用于推理() {
        ModelEntity model = model(false, ModelInterfaceType.CHAT_COMPLETIONS);
        when(modelRepository.findByIdAndOwnerUserId(MODEL_ID, OWNER_ID)).thenReturn(Optional.of(model));

        assertUnavailable();
        verifyNoInteractions(endpointRepository);
    }

    @Test
    void 禁用端点不可用于推理() {
        ModelEntity model = model(true, ModelInterfaceType.CHAT_COMPLETIONS);
        when(modelRepository.findByIdAndOwnerUserId(MODEL_ID, OWNER_ID)).thenReturn(Optional.of(model));
        when(endpointRepository.findByIdAndOwnerUserId(ENDPOINT_ID, OWNER_ID)).thenReturn(Optional.of(endpoint(false, true)));

        assertUnavailable();
    }

    @Test
    void 非聊天模型不可用于推理() {
        ModelEntity model = model(true, ModelInterfaceType.IMAGE_GENERATION);
        when(modelRepository.findByIdAndOwnerUserId(MODEL_ID, OWNER_ID)).thenReturn(Optional.of(model));

        assertUnavailable();
        verifyNoInteractions(endpointRepository);
    }

    @Test
    void 缺少当前凭据不可用于推理() {
        ModelEntity model = model(true, ModelInterfaceType.CHAT_COMPLETIONS);
        when(modelRepository.findByIdAndOwnerUserId(MODEL_ID, OWNER_ID)).thenReturn(Optional.of(model));
        when(endpointRepository.findByIdAndOwnerUserId(ENDPOINT_ID, OWNER_ID)).thenReturn(Optional.of(endpoint(true, false)));

        assertUnavailable();
    }

    private void assertUnavailable() {
        ModelRegistryApplicationException exception = assertThrows(
                ModelRegistryApplicationException.class,
                () -> service.resolve(OWNER_ID, MODEL_ID)
        );
        assertEquals(ModelRegistryErrorCode.INFERENCE_TARGET_UNAVAILABLE, exception.code());
    }

    private static ModelEntity model(boolean enabled, ModelInterfaceType interfaceType) {
        return ModelEntity.create(
                MODEL_ID,
                endpoint(true, true),
                "provider-chat",
                "聊天模型",
                capabilities(interfaceType),
                enabled,
                false,
                NOW
        );
    }

    private static EndpointEntity endpoint(boolean enabled, boolean credentialConfigured) {
        EndpointEntity endpoint = EndpointEntity.create(
                ENDPOINT_ID,
                OWNER_ID,
                new EndpointSettings(
                        "主端点",
                        URI.create("https://api.example.com/v1"),
                        EndpointProtocol.OPENAI_COMPATIBLE,
                        Duration.ofSeconds(30),
                        enabled
                ),
                NOW
        );
        if (credentialConfigured) {
            endpoint.attachCredential(EndpointCredentialEntity.create(
                    CREDENTIAL_ID,
                    ENDPOINT_ID,
                    new EncryptedSecret(
                            1,
                            "local-v1",
                            new byte[12],
                            new byte[48],
                            new byte[12],
                            new byte[16]
                    ),
                    NOW
            ), NOW);
        }
        return endpoint;
    }

    private static ModelCapabilities capabilities(ModelInterfaceType interfaceType) {
        if (interfaceType == ModelInterfaceType.IMAGE_GENERATION) {
            return new ModelCapabilities(
                    interfaceType,
                    Set.of(InputModality.TEXT),
                    OutputModality.IMAGE,
                    false,
                    false,
                    false,
                    8_192,
                    1_024
            );
        }
        return new ModelCapabilities(
                interfaceType,
                Set.of(InputModality.TEXT),
                OutputModality.TEXT,
                true,
                true,
                false,
                8_192,
                1_024
        );
    }
}
