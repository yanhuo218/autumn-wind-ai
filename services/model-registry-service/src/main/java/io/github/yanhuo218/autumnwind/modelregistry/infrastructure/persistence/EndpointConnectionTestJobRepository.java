package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EndpointConnectionTestJobRepository
        extends JpaRepository<EndpointConnectionTestJobEntity, UUID> {
}
