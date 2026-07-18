package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.EndpointConnectionTestStatus;
import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ConnectionTestFailureCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "endpoint_connection_test_jobs", schema = "model_registry")
public class EndpointConnectionTestJobEntity {

    @Id
    private UUID id;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(name = "endpoint_version", nullable = false)
    private long endpointVersion;

    @Column(name = "credential_id", nullable = false)
    private UUID credentialId;

    @Column(name = "requested_by_user_id", nullable = false)
    private UUID requestedByUserId;

    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EndpointConnectionTestStatus status;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "lease_id")
    private UUID leaseId;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Version
    @Column(nullable = false)
    private long version;

    protected EndpointConnectionTestJobEntity() {
    }

    public static EndpointConnectionTestJobEntity queued(
            UUID id,
            UUID ownerUserId,
            UUID endpointId,
            long endpointVersion,
            UUID credentialId,
            UUID requestedByUserId,
            String correlationId,
            Instant createdAt
    ) {
        EndpointConnectionTestJobEntity entity = new EndpointConnectionTestJobEntity();
        entity.id = Objects.requireNonNull(id, "连接测试任务标识不能为空。");
        entity.ownerUserId = Objects.requireNonNull(ownerUserId, "端点所有者不能为空。");
        entity.endpointId = Objects.requireNonNull(endpointId, "端点标识不能为空。");
        entity.endpointVersion = endpointVersion;
        entity.credentialId = Objects.requireNonNull(credentialId, "端点凭据标识不能为空。");
        entity.requestedByUserId = Objects.requireNonNull(requestedByUserId, "连接测试操作者不能为空。");
        entity.correlationId = Objects.requireNonNull(correlationId, "关联标识不能为空。");
        entity.status = EndpointConnectionTestStatus.QUEUED;
        entity.createdAt = Objects.requireNonNull(createdAt, "连接测试创建时间不能为空。");
        return entity;
    }

    public UUID id() {
        return id;
    }

    public UUID endpointId() {
        return endpointId;
    }

    public UUID ownerUserId() {
        return ownerUserId;
    }

    public long endpointVersion() {
        return endpointVersion;
    }

    public EndpointConnectionTestStatus status() {
        return status;
    }

    public UUID credentialId() {
        return credentialId;
    }

    public String correlationId() {
        return correlationId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public UUID leaseId() {
        return leaseId;
    }

    public Instant leaseExpiresAt() {
        return leaseExpiresAt;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public String failureCode() {
        return failureCode;
    }

    public long version() {
        return version;
    }

    public void claim(UUID newLeaseId, Instant now, Instant newLeaseExpiresAt) {
        Objects.requireNonNull(now, "领取时间不能为空。");
        if (status == EndpointConnectionTestStatus.RUNNING
                && (leaseId == null || leaseExpiresAt == null || leaseExpiresAt.isAfter(now))) {
            throw new IllegalStateException("运行任务只有在完整租约过期后才能再次领取。");
        }
        if (status != EndpointConnectionTestStatus.QUEUED && status != EndpointConnectionTestStatus.RUNNING) {
            throw new IllegalStateException("只有等待或租约过期的运行任务可以领取。");
        }
        leaseId = Objects.requireNonNull(newLeaseId, "租约标识不能为空。");
        startedAt = now;
        leaseExpiresAt = Objects.requireNonNull(newLeaseExpiresAt, "租约到期时间不能为空。");
        if (!leaseExpiresAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("租约到期时间必须晚于领取时间。");
        }
        status = EndpointConnectionTestStatus.RUNNING;
        completedAt = null;
        failureCode = null;
        attemptCount = Math.addExact(attemptCount, 1);
    }

    public void renew(Instant newLeaseExpiresAt) {
        requireRunning();
        leaseExpiresAt = Objects.requireNonNull(newLeaseExpiresAt, "租约到期时间不能为空。");
        if (!leaseExpiresAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("租约到期时间必须晚于领取时间。");
        }
    }

    public void succeed(Instant now) {
        complete(EndpointConnectionTestStatus.SUCCEEDED, null, now);
    }

    public void fail(ConnectionTestFailureCode stableFailureCode, Instant now) {
        complete(EndpointConnectionTestStatus.FAILED,
                Objects.requireNonNull(stableFailureCode, "稳定失败码不能为空。").name(), now);
    }

    private void complete(EndpointConnectionTestStatus completedStatus, String stableFailureCode, Instant now) {
        requireRunning();
        status = completedStatus;
        failureCode = stableFailureCode;
        completedAt = Objects.requireNonNull(now, "任务完成时间不能为空。");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("任务完成时间不能早于开始时间。");
        }
        leaseId = null;
        leaseExpiresAt = null;
    }

    private void requireRunning() {
        if (status != EndpointConnectionTestStatus.RUNNING || leaseId == null || leaseExpiresAt == null) {
            throw new IllegalStateException("任务没有活动租约。");
        }
    }
}
