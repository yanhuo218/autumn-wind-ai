package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointConnectionTestJobEntity;

import java.time.Instant;
import java.util.UUID;

public record ConnectionTestJobLeaseView(
        UUID jobId,
        UUID ownerUserId,
        UUID endpointId,
        long endpointVersion,
        UUID credentialId,
        String correlationId,
        UUID leaseId,
        Instant leaseExpiresAt,
        int attemptCount,
        long jobVersion
) {

    public static ConnectionTestJobLeaseView from(EndpointConnectionTestJobEntity entity) {
        return new ConnectionTestJobLeaseView(
                entity.id(),
                entity.ownerUserId(),
                entity.endpointId(),
                entity.endpointVersion(),
                entity.credentialId(),
                entity.correlationId(),
                entity.leaseId(),
                entity.leaseExpiresAt(),
                entity.attemptCount(),
                entity.version()
        );
    }
}
