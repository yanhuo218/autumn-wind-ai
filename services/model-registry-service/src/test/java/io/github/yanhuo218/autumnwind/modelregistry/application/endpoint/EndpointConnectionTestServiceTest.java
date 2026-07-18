package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointProtocol;
import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointSettings;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointConnectionTestJobEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointConnectionTestJobRepository;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointCredentialEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointRepository;
import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndpointConnectionTestServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENDPOINT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private EndpointConnectionTestJobRepository jobRepository;

    private EndpointConnectionTestService service;

    @BeforeEach
    void setUp() {
        service = new EndpointConnectionTestService(
                endpointRepository,
                jobRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void 创建任务时固定端点版本和凭据引用() {
        EndpointEntity endpoint = endpointWithCredential();
        when(endpointRepository.findByIdAndOwnerUserId(ENDPOINT_ID, OWNER_ID)).thenReturn(Optional.of(endpoint));
        when(jobRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EndpointConnectionTestView view = service.enqueue(new EndpointConnectionTestCommand(
                OWNER_ID,
                ENDPOINT_ID,
                OWNER_ID,
                "01JZ8M4A7X4S6NR2YQF1D9K3CP",
                0
        ));

        assertEquals(EndpointConnectionTestStatus.QUEUED, view.status());
        assertEquals(ENDPOINT_ID, view.endpointId());
        assertEquals(0, view.endpointVersion());
        assertEquals(NOW, view.createdAt());
    }

    @Test
    void 端点版本不匹配时不创建任务() {
        when(endpointRepository.findByIdAndOwnerUserId(ENDPOINT_ID, OWNER_ID))
                .thenReturn(Optional.of(endpointWithCredential()));

        ModelRegistryApplicationException exception = assertThrows(
                ModelRegistryApplicationException.class,
                () -> service.enqueue(new EndpointConnectionTestCommand(
                        OWNER_ID, ENDPOINT_ID, OWNER_ID, "01JZ8M4A7X4S6NR2YQF1D9K3CP", 1))
        );

        assertEquals(ModelRegistryErrorCode.ENDPOINT_VERSION_CONFLICT, exception.code());
        verify(jobRepository, never()).saveAndFlush(any());
    }

    private static EndpointEntity endpointWithCredential() {
        EndpointEntity endpoint = EndpointEntity.create(ENDPOINT_ID, OWNER_ID, new EndpointSettings(
                "主要端点", URI.create("https://api.example.com/v1"), EndpointProtocol.OPENAI_COMPATIBLE,
                Duration.ofSeconds(30), true), NOW);
        endpoint.attachCredential(EndpointCredentialEntity.create(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                ENDPOINT_ID,
                new EncryptedSecret(1, "test-key", new byte[12], new byte[48], new byte[12], new byte[32]),
                NOW
        ), NOW);
        return endpoint;
    }
}
