package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence;

import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelInterfaceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModelRepository extends JpaRepository<ModelEntity, UUID> {

    Optional<ModelEntity> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

    List<ModelEntity> findAllByOwnerUserIdOrderByCreatedAtAsc(UUID ownerUserId);

    boolean existsByOwnerUserIdAndEndpointIdAndProviderModelIdAndIdNot(
            UUID ownerUserId,
            UUID endpointId,
            String providerModelId,
            UUID excludedId
    );

    boolean existsByOwnerUserIdAndInterfaceTypeAndDefaultModelTrueAndIdNot(
            UUID ownerUserId,
            ModelInterfaceType interfaceType,
            UUID excludedId
    );
}
