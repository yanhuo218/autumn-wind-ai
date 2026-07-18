package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EndpointConnectionTestJobRepository
        extends JpaRepository<EndpointConnectionTestJobEntity, UUID> {

    @Query(value = """
            SELECT *
            FROM model_registry.endpoint_connection_test_jobs
            WHERE status = 'QUEUED'
               OR (status = 'RUNNING' AND lease_expires_at <= :now)
            ORDER BY created_at ASC, id ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<EndpointConnectionTestJobEntity> lockOldestClaimable(@Param("now") Instant now);

    @Query(value = """
            SELECT *
            FROM model_registry.endpoint_connection_test_jobs
            WHERE id = :jobId
              AND status = 'RUNNING'
              AND lease_id = :leaseId
              AND version = :jobVersion
              AND lease_expires_at > :now
            FOR UPDATE
            """, nativeQuery = true)
    Optional<EndpointConnectionTestJobEntity> lockActiveLease(
            @Param("jobId") UUID jobId,
            @Param("leaseId") UUID leaseId,
            @Param("jobVersion") long jobVersion,
            @Param("now") Instant now
    );
}
