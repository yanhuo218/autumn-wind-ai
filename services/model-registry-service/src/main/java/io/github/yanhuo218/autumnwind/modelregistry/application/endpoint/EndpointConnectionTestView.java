package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointConnectionTestJobEntity;

import java.time.Instant;
import java.util.UUID;

public record EndpointConnectionTestView(
        UUID jobId,
        UUID endpointId,
        EndpointConnectionTestStatus status,
        long endpointVersion,
        Instant createdAt
) {

    public static EndpointConnectionTestView from(EndpointConnectionTestJobEntity entity) {
        return new EndpointConnectionTestView(
                entity.id(), entity.endpointId(), entity.status(), entity.endpointVersion(), entity.createdAt());
    }
}
