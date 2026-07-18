package io.github.yanhuo218.autumnwind.modelregistry.application.model;

import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelCapabilities;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.ModelEntity;

import java.time.Instant;
import java.util.UUID;

public record ModelView(
        UUID id,
        UUID ownerUserId,
        UUID endpointId,
        String providerModelId,
        String displayName,
        ModelCapabilities capabilities,
        boolean enabled,
        boolean defaultModel,
        int capabilitySchemaVersion,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static ModelView from(ModelEntity entity) {
        return new ModelView(
                entity.id(),
                entity.ownerUserId(),
                entity.endpointId(),
                entity.providerModelId(),
                entity.displayName(),
                entity.capabilities(),
                entity.enabled(),
                entity.defaultModel(),
                entity.capabilitySchemaVersion(),
                entity.version(),
                entity.createdAt(),
                entity.updatedAt()
        );
    }
}
