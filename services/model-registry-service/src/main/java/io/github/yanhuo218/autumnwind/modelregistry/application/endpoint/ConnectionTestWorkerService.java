package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointConnectionTestJobEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointConnectionTestJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConnectionTestWorkerService {

    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(30);

    private final EndpointConnectionTestJobRepository repository;
    private final Clock clock;
    private final Duration leaseDuration;

    public ConnectionTestWorkerService(EndpointConnectionTestJobRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "连接测试任务仓库不能为空。");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空。");
        this.leaseDuration = DEFAULT_LEASE_DURATION;
    }

    @Transactional
    public Optional<ConnectionTestJobLeaseView> claim() {
        Instant now = clock.instant();
        return repository.lockOldestClaimable(now).map(job -> {
            job.claim(UUID.randomUUID(), now, now.plus(leaseDuration));
            return ConnectionTestJobLeaseView.from(repository.saveAndFlush(job));
        });
    }

    @Transactional
    public ConnectionTestJobVersionView renew(ConnectionTestLeaseCommand command) {
        Instant now = clock.instant();
        EndpointConnectionTestJobEntity job = activeLease(command, now);
        job.renew(now.plus(leaseDuration));
        return ConnectionTestJobVersionView.from(repository.saveAndFlush(job));
    }

    @Transactional
    public ConnectionTestJobVersionView succeed(ConnectionTestLeaseCommand command) {
        Instant now = clock.instant();
        EndpointConnectionTestJobEntity job = activeLease(command, now);
        job.succeed(now);
        return ConnectionTestJobVersionView.from(repository.saveAndFlush(job));
    }

    @Transactional
    public ConnectionTestJobVersionView fail(ConnectionTestFailureCommand command) {
        Objects.requireNonNull(command, "连接测试失败命令不能为空。");
        Instant now = clock.instant();
        EndpointConnectionTestJobEntity job = activeLease(
                new ConnectionTestLeaseCommand(command.jobId(), command.leaseId(), command.jobVersion()), now);
        job.fail(command.failureCode(), now);
        return ConnectionTestJobVersionView.from(repository.saveAndFlush(job));
    }

    private EndpointConnectionTestJobEntity activeLease(ConnectionTestLeaseCommand command, Instant now) {
        Objects.requireNonNull(command, "连接测试租约命令不能为空。");
        return repository.lockActiveLease(
                        command.jobId(), command.leaseId(), command.jobVersion(), now)
                .orElseThrow(() -> new ModelRegistryApplicationException(
                        ModelRegistryErrorCode.CONNECTION_TEST_LEASE_CONFLICT,
                        "连接测试任务租约或版本不匹配。"
                ));
    }
}
