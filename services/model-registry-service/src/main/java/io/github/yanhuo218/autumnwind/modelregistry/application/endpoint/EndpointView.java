package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointProtocol;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointEntity;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record EndpointView(
        UUID id,
        UUID ownerUserId,
        String displayName,
        URI baseUrl,
        EndpointProtocol protocol,
        int requestTimeoutSeconds,
        boolean enabled,
        boolean credentialConfigured,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static EndpointView from(EndpointEntity entity) {
        return new EndpointView(
                entity.id(),
                entity.ownerUserId(),
                entity.displayName(),
                entity.baseUrl(),
                entity.protocol(),
                entity.requestTimeoutSeconds(),
                entity.enabled(),
                entity.currentCredential() != null,
                entity.version(),
                entity.createdAt(),
                entity.updatedAt()
        );
    }
}
