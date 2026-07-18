package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EndpointRepository extends JpaRepository<EndpointEntity, UUID> {

    Optional<EndpointEntity> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);
}
