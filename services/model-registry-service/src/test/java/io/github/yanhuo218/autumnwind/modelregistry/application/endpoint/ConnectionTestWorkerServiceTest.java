package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointConnectionTestJobEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointConnectionTestJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionTestWorkerServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T01:00:00Z");
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final UUID JOB_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ENDPOINT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CREDENTIAL_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID REQUESTER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock
    private EndpointConnectionTestJobRepository repository;

    private ConnectionTestWorkerService service;

    @BeforeEach
    void setUp() {
        service = new ConnectionTestWorkerService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void 领取最旧可用任务时写入租约并递增尝试次数() {
        EndpointConnectionTestJobEntity job = queued(NOW.minusSeconds(60));
        when(repository.lockOldestClaimable(NOW)).thenReturn(Optional.of(job));
        when(repository.<EndpointConnectionTestJobEntity>saveAndFlush(job)).thenReturn(job);

        ConnectionTestJobLeaseView lease = service.claim().orElseThrow();

        assertEquals(JOB_ID, lease.jobId());
        assertEquals(OWNER_ID, lease.ownerUserId());
        assertEquals(ENDPOINT_ID, lease.endpointId());
        assertEquals(7, lease.endpointVersion());
        assertEquals(CREDENTIAL_ID, lease.credentialId());
        assertEquals("01JZ8M4A7X4S6NR2YQF1D9K3CP", lease.correlationId());
        assertEquals(NOW.plus(LEASE_DURATION), lease.leaseExpiresAt());
        assertEquals(1, lease.attemptCount());
        assertEquals(EndpointConnectionTestStatus.RUNNING, job.status());
        assertEquals(NOW, job.startedAt());
    }

    @Test
    void 过期运行任务恢复时更换租约重置开始时间并递增尝试次数() {
        EndpointConnectionTestJobEntity job = queued(NOW.minusSeconds(120));
        UUID oldLeaseId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        job.claim(oldLeaseId, NOW.minusSeconds(90), NOW.minusSeconds(60));
        when(repository.lockOldestClaimable(NOW)).thenReturn(Optional.of(job));
        when(repository.<EndpointConnectionTestJobEntity>saveAndFlush(job)).thenReturn(job);

        ConnectionTestJobLeaseView lease = service.claim().orElseThrow();

        assertNotEquals(oldLeaseId, lease.leaseId());
        assertEquals(2, lease.attemptCount());
        assertEquals(NOW, job.startedAt());
        assertEquals(NOW.plus(LEASE_DURATION), job.leaseExpiresAt());
    }

    @Test
    void 实体层拒绝覆盖仍然有效的运行租约() {
        EndpointConnectionTestJobEntity job = claimed();

        assertThrows(IllegalStateException.class, () -> job.claim(
                UUID.fromString("99999999-9999-9999-9999-999999999999"),
                NOW,
                NOW.plus(LEASE_DURATION)
        ));
    }

    @Test
    void 没有可领取任务时返回空结果() {
        when(repository.lockOldestClaimable(NOW)).thenReturn(Optional.empty());

        assertTrue(service.claim().isEmpty());
        verify(repository, never()).saveAndFlush(any(EndpointConnectionTestJobEntity.class));
    }

    @Test
    void 续租必须匹配任务租约版本且租约未过期() {
        EndpointConnectionTestJobEntity job = claimed();
        when(repository.lockActiveLease(JOB_ID, job.leaseId(), 0, NOW)).thenReturn(Optional.of(job));
        when(repository.<EndpointConnectionTestJobEntity>saveAndFlush(job)).thenReturn(job);

        ConnectionTestJobVersionView result = service.renew(
                new ConnectionTestLeaseCommand(JOB_ID, job.leaseId(), 0));

        assertEquals(JOB_ID, result.jobId());
        assertEquals(0, result.jobVersion());
        assertEquals(NOW.plus(LEASE_DURATION), job.leaseExpiresAt());
    }

    @Test
    void 租约或版本不匹配时拒绝续租() {
        UUID staleLease = UUID.fromString("77777777-7777-7777-7777-777777777777");
        when(repository.lockActiveLease(JOB_ID, staleLease, 3, NOW)).thenReturn(Optional.empty());

        ModelRegistryApplicationException exception = assertThrows(
                ModelRegistryApplicationException.class,
                () -> service.renew(new ConnectionTestLeaseCommand(JOB_ID, staleLease, 3))
        );

        assertEquals(ModelRegistryErrorCode.CONNECTION_TEST_LEASE_CONFLICT, exception.code());
        verify(repository, never()).saveAndFlush(any(EndpointConnectionTestJobEntity.class));
    }

    @Test
    void 成功回写完成任务并清除租约() {
        EndpointConnectionTestJobEntity job = claimed();
        when(repository.lockActiveLease(JOB_ID, job.leaseId(), 0, NOW)).thenReturn(Optional.of(job));
        when(repository.<EndpointConnectionTestJobEntity>saveAndFlush(job)).thenReturn(job);

        ConnectionTestJobVersionView result = service.succeed(
                new ConnectionTestLeaseCommand(JOB_ID, job.leaseId(), 0));

        assertEquals(0, result.jobVersion());
        assertEquals(EndpointConnectionTestStatus.SUCCEEDED, job.status());
        assertEquals(NOW, job.completedAt());
        assertNull(job.leaseId());
        assertNull(job.leaseExpiresAt());
        assertNull(job.failureCode());
    }

    @Test
    void 失败回写只保存稳定错误码并清除租约() {
        EndpointConnectionTestJobEntity job = claimed();
        when(repository.lockActiveLease(JOB_ID, job.leaseId(), 0, NOW)).thenReturn(Optional.of(job));
        when(repository.<EndpointConnectionTestJobEntity>saveAndFlush(job)).thenReturn(job);

        service.fail(new ConnectionTestFailureCommand(
                JOB_ID,
                job.leaseId(),
                0,
                ConnectionTestFailureCode.PROVIDER_AUTHENTICATION_FAILED
        ));

        assertEquals(EndpointConnectionTestStatus.FAILED, job.status());
        assertEquals("PROVIDER_AUTHENTICATION_FAILED", job.failureCode());
        assertEquals(NOW, job.completedAt());
        assertNull(job.leaseId());
        assertNull(job.leaseExpiresAt());
    }

    @Test
    void 实体失败转换只接受封闭失败码() {
        EndpointConnectionTestJobEntity job = claimed();

        job.fail(ConnectionTestFailureCode.PROVIDER_ERROR, NOW);

        assertEquals("PROVIDER_ERROR", job.failureCode());
    }

    @Test
    void 完成状态不能再次回写() {
        EndpointConnectionTestJobEntity job = claimed();
        UUID leaseId = job.leaseId();
        when(repository.lockActiveLease(JOB_ID, leaseId, 0, NOW))
                .thenReturn(Optional.of(job), Optional.empty());
        when(repository.<EndpointConnectionTestJobEntity>saveAndFlush(job)).thenReturn(job);
        service.succeed(new ConnectionTestLeaseCommand(JOB_ID, leaseId, 0));

        ModelRegistryApplicationException exception = assertThrows(
                ModelRegistryApplicationException.class,
                () -> service.succeed(new ConnectionTestLeaseCommand(JOB_ID, leaseId, 0))
        );

        assertEquals(ModelRegistryErrorCode.CONNECTION_TEST_LEASE_CONFLICT, exception.code());
        assertFalse(job.status() == EndpointConnectionTestStatus.RUNNING);
    }

    private static EndpointConnectionTestJobEntity queued(Instant createdAt) {
        return EndpointConnectionTestJobEntity.queued(
                JOB_ID,
                OWNER_ID,
                ENDPOINT_ID,
                7,
                CREDENTIAL_ID,
                REQUESTER_ID,
                "01JZ8M4A7X4S6NR2YQF1D9K3CP",
                createdAt
        );
    }

    private static EndpointConnectionTestJobEntity claimed() {
        EndpointConnectionTestJobEntity job = queued(NOW.minusSeconds(60));
        job.claim(UUID.fromString("88888888-8888-8888-8888-888888888888"), NOW.minusSeconds(5), NOW.plusSeconds(25));
        return job;
    }
}
