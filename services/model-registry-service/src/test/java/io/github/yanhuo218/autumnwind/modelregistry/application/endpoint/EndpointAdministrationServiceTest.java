package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointProtocol;
import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointSettings;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointCredentialEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointCredentialRepository;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointRepository;
import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import io.github.yanhuo218.autumnwind.security.secrets.SecretContext;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStore;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndpointAdministrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENDPOINT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private EndpointCredentialRepository credentialRepository;

    @Mock
    private SecretStore secretStore;

    private EndpointAdministrationService service;

    @BeforeEach
    void setUp() {
        service = new EndpointAdministrationService(
                endpointRepository,
                credentialRepository,
                secretStore,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void 创建端点时绑定租户上下文加密ApiKey并清空临时字节() {
        AtomicReference<byte[]> plaintextCopy = new AtomicReference<>();
        AtomicReference<byte[]> plaintextReference = new AtomicReference<>();
        AtomicReference<SecretContext> contextReference = new AtomicReference<>();
        when(secretStore.encrypt(any(), any())).thenAnswer(invocation -> {
            byte[] plaintext = invocation.getArgument(0);
            plaintextReference.set(plaintext);
            plaintextCopy.set(Arrays.copyOf(plaintext, plaintext.length));
            contextReference.set(invocation.getArgument(1));
            return encryptedSecret();
        });
        when(endpointRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(credentialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EndpointView view = service.create(new CreateEndpointCommand(
                OWNER_ID,
                settings(),
                "not-a-real-api-key"
        ));

        assertEquals("not-a-real-api-key", new String(plaintextCopy.get(), StandardCharsets.UTF_8));
        assertTrue(Arrays.equals(new byte[plaintextReference.get().length], plaintextReference.get()));
        assertEquals(OWNER_ID.toString(), contextReference.get().tenantId());
        assertEquals("model-endpoint-api-key", contextReference.get().purpose());
        assertEquals(view.id().toString(), contextReference.get().ownerId());
        assertEquals(OWNER_ID, view.ownerUserId());
        assertTrue(view.credentialConfigured());
        assertEquals(NOW, view.createdAt());
    }

    @Test
    void 加密失败时不写入端点或凭据() {
        when(secretStore.encrypt(any(), any())).thenThrow(new SecretStoreException("测试失败"));

        ModelRegistryApplicationException exception = assertThrows(
                ModelRegistryApplicationException.class,
                () -> service.create(new CreateEndpointCommand(OWNER_ID, settings(), "not-a-real-api-key"))
        );

        assertEquals(ModelRegistryErrorCode.ENDPOINT_SECRET_FAILURE, exception.code());
        verifyNoInteractions(endpointRepository, credentialRepository);
    }

    @Test
    void 读取端点始终绑定所有者且不解密凭据() {
        EndpointEntity endpoint = endpoint(credential());
        when(endpointRepository.findByIdAndOwnerUserId(ENDPOINT_ID, OWNER_ID)).thenReturn(Optional.of(endpoint));

        EndpointView view = service.get(OWNER_ID, ENDPOINT_ID);

        assertEquals(ENDPOINT_ID, view.id());
        assertTrue(view.credentialConfigured());
        verifyNoInteractions(secretStore, credentialRepository);
    }

    @Test
    void 读取其他租户端点时返回不存在() {
        when(endpointRepository.findByIdAndOwnerUserId(ENDPOINT_ID, OWNER_ID)).thenReturn(Optional.empty());

        ModelRegistryApplicationException exception = assertThrows(
                ModelRegistryApplicationException.class,
                () -> service.get(OWNER_ID, ENDPOINT_ID)
        );

        assertEquals(ModelRegistryErrorCode.ENDPOINT_NOT_FOUND, exception.code());
    }

    @Test
    void 版本不匹配时不加密也不写库() {
        EndpointEntity endpoint = endpoint(credential());
        when(endpointRepository.findByIdAndOwnerUserId(ENDPOINT_ID, OWNER_ID)).thenReturn(Optional.of(endpoint));

        ModelRegistryApplicationException exception = assertThrows(
                ModelRegistryApplicationException.class,
                () -> service.replaceKey(new ReplaceEndpointKeyCommand(
                        OWNER_ID,
                        ENDPOINT_ID,
                        "replacement-key",
                        1
                ))
        );

        assertEquals(ModelRegistryErrorCode.ENDPOINT_VERSION_CONFLICT, exception.code());
        verifyNoInteractions(secretStore, credentialRepository);
        verify(endpointRepository, never()).saveAndFlush(any());
    }

    @Test
    void 替换ApiKey时切换引用并标记旧凭据() {
        EndpointCredentialEntity previous = credential();
        EndpointEntity endpoint = endpoint(previous);
        when(endpointRepository.findByIdAndOwnerUserId(ENDPOINT_ID, OWNER_ID)).thenReturn(Optional.of(endpoint));
        when(secretStore.encrypt(any(), any())).thenReturn(encryptedSecret());
        when(credentialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(endpointRepository.saveAndFlush(endpoint)).thenReturn(endpoint);

        EndpointView view = service.replaceKey(new ReplaceEndpointKeyCommand(
                OWNER_ID,
                ENDPOINT_ID,
                "replacement-key",
                0
        ));

        assertEquals(NOW, previous.replacedAt());
        assertTrue(view.credentialConfigured());
        assertNotNull(endpoint.currentCredential());
        assertNull(endpoint.currentCredential().replacedAt());
    }

    private static EndpointEntity endpoint(EndpointCredentialEntity credential) {
        EndpointEntity endpoint = EndpointEntity.create(ENDPOINT_ID, OWNER_ID, settings(), NOW);
        endpoint.attachCredential(credential, NOW);
        return endpoint;
    }

    private static EndpointCredentialEntity credential() {
        return EndpointCredentialEntity.create(UUID.randomUUID(), ENDPOINT_ID, encryptedSecret(), NOW.minusSeconds(30));
    }

    private static EncryptedSecret encryptedSecret() {
        return new EncryptedSecret(
                1,
                "test-key",
                new byte[12],
                new byte[48],
                new byte[12],
                new byte[32]
        );
    }

    private static EndpointSettings settings() {
        return new EndpointSettings(
                "主要端点",
                URI.create("https://api.example.com/v1"),
                EndpointProtocol.OPENAI_COMPATIBLE,
                Duration.ofSeconds(30),
                true
        );
    }
}
