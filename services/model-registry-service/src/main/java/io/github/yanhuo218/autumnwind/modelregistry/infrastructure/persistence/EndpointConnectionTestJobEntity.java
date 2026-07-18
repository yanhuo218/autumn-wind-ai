package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.EndpointConnectionTestStatus;
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

    public long endpointVersion() {
        return endpointVersion;
    }

    public EndpointConnectionTestStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
