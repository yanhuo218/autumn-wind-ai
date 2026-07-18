package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointConnectionTestJobEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointConnectionTestJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TASK5_POSTGRES_URL", matches = ".+")
class ConnectionTestWorkerPostgresIntegrationTest {

    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ENDPOINT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CREDENTIAL_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EndpointConnectionTestJobRepository repository;

    @Autowired
    private ConnectionTestWorkerService service;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnvironment("TASK5_POSTGRES_URL"));
        registry.add("spring.datasource.username", () -> requiredEnvironment("TASK5_POSTGRES_USERNAME"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("TASK5_POSTGRES_PASSWORD"));
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("autumn-wind.model-registry.secret-store.master-key-file",
                () -> requiredEnvironment("TASK5_MASTER_KEY_FILE"));
    }

    @Test
    void 真实数据库验证迁移版本并发领取和过期恢复() throws Exception {
        assertMigratedFixture();
        jdbcTemplate.update("DELETE FROM model_registry.endpoint_connection_test_jobs");

        verifyVersionTransitions();
        jdbcTemplate.update("DELETE FROM model_registry.endpoint_connection_test_jobs");

        verifySkipLocked();
        jdbcTemplate.update("DELETE FROM model_registry.endpoint_connection_test_jobs");

        verifyExpiredLeaseRecovery();
    }

    private void assertMigratedFixture() {
        assertEquals("QUEUED", status("20000000-0000-0000-0000-000000000002"));
        assertEquals(0, attemptCount("20000000-0000-0000-0000-000000000002"));
        assertEquals(5L, databaseVersion(UUID.fromString("20000000-0000-0000-0000-000000000002")));
        assertEquals(1, attemptCount("20000000-0000-0000-0000-000000000003"));
        assertEquals(1, attemptCount("20000000-0000-0000-0000-000000000004"));
        assertEquals("INTERNAL_DEPENDENCY_ERROR", jdbcTemplate.queryForObject("""
                SELECT failure_code
                FROM model_registry.endpoint_connection_test_jobs
                WHERE id = '20000000-0000-0000-0000-000000000004'
                """, String.class));
        assertEquals(0, attemptCount("20000000-0000-0000-0000-000000000005"));

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                UPDATE model_registry.endpoint_connection_test_jobs
                SET attempt_count = 1
                WHERE id = '20000000-0000-0000-0000-000000000005'
                """));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                UPDATE model_registry.endpoint_connection_test_jobs
                SET failure_code = 'RAW_PROVIDER_BODY'
                WHERE id = '20000000-0000-0000-0000-000000000004'
                """));
    }

    private void verifyVersionTransitions() {
        UUID jobId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        repository.saveAndFlush(queued(jobId, Instant.now().minusSeconds(30)));

        ConnectionTestJobLeaseView claimed = service.claim().orElseThrow();
        assertEquals(jobId, claimed.jobId());
        assertEquals(1, claimed.jobVersion());

        ConnectionTestJobVersionView renewed = service.renew(new ConnectionTestLeaseCommand(
                jobId, claimed.leaseId(), claimed.jobVersion()));
        assertEquals(2, renewed.jobVersion());

        ModelRegistryApplicationException conflict = assertThrows(
                ModelRegistryApplicationException.class,
                () -> service.succeed(new ConnectionTestLeaseCommand(
                        jobId, claimed.leaseId(), claimed.jobVersion()))
        );
        assertEquals(ModelRegistryErrorCode.CONNECTION_TEST_LEASE_CONFLICT, conflict.code());
        assertEquals(2L, databaseVersion(jobId));
        assertEquals("RUNNING", status(jobId.toString()));

        ConnectionTestJobVersionView completed = service.succeed(new ConnectionTestLeaseCommand(
                jobId, claimed.leaseId(), renewed.jobVersion()));
        assertEquals(3, completed.jobVersion());
        assertEquals("SUCCEEDED", status(jobId.toString()));
    }

    private void verifySkipLocked() throws Exception {
        UUID firstId = UUID.fromString("30000000-0000-0000-0000-000000000002");
        UUID secondId = UUID.fromString("30000000-0000-0000-0000-000000000003");
        repository.saveAndFlush(queued(firstId, Instant.now().minusSeconds(60)));
        repository.saveAndFlush(queued(secondId, Instant.now().minusSeconds(30)));

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstLock = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                UUID selected = repository.lockOldestClaimable(Instant.now()).orElseThrow().id();
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待释放数据库行锁超时。");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待数据库行锁时被中断。");
                }
                return selected;
            }));

            assertTrue(locked.await(10, TimeUnit.SECONDS));
            ConnectionTestJobLeaseView claimed = service.claim().orElseThrow();
            assertEquals(secondId, claimed.jobId());
            release.countDown();
            assertEquals(firstId, firstLock.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    private void verifyExpiredLeaseRecovery() {
        UUID jobId = UUID.fromString("30000000-0000-0000-0000-000000000004");
        repository.saveAndFlush(queued(jobId, Instant.now().minusSeconds(30)));
        ConnectionTestJobLeaseView first = service.claim().orElseThrow();
        Instant expiredAt = clock.instant().minusSeconds(60);
        jdbcTemplate.update("""
                UPDATE model_registry.endpoint_connection_test_jobs
                SET started_at = ?,
                    lease_expires_at = ?
                WHERE id = ?
                """, Timestamp.from(expiredAt.minusSeconds(1)), Timestamp.from(expiredAt), jobId);

        ConnectionTestJobLeaseView recovered = service.claim().orElseThrow();

        assertEquals(jobId, recovered.jobId());
        assertNotEquals(first.leaseId(), recovered.leaseId());
        assertEquals(2, recovered.attemptCount());
        assertEquals(2, recovered.jobVersion());
    }

    private EndpointConnectionTestJobEntity queued(UUID id, Instant createdAt) {
        return EndpointConnectionTestJobEntity.queued(
                id,
                OWNER_ID,
                ENDPOINT_ID,
                0,
                CREDENTIAL_ID,
                OWNER_ID,
                "TASK5INTEGRATION" + id.toString().substring(0, 8),
                createdAt
        );
    }

    private String status(String id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM model_registry.endpoint_connection_test_jobs WHERE id = ?::uuid",
                String.class,
                id
        );
    }

    private int attemptCount(String id) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM model_registry.endpoint_connection_test_jobs WHERE id = ?::uuid",
                Integer.class,
                id
        );
    }

    private long databaseVersion(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM model_registry.endpoint_connection_test_jobs WHERE id = ?",
                Long.class,
                id
        );
    }

    private static String requiredEnvironment(String name) {
        return java.util.Objects.requireNonNull(System.getenv(name), name + " 不能为空。");
    }
}
