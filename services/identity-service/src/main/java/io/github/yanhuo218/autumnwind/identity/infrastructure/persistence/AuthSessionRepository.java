package io.github.yanhuo218.autumnwind.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, UUID> {

    Optional<AuthSessionEntity> findByTokenHash(String tokenHash);

    @Modifying
    @Query("""
            update AuthSessionEntity session
               set session.revokedAt = :revokedAt
             where session.userId = :userId
               and session.revokedAt is null
            """)
    int revokeAllByUserId(@Param("userId") UUID userId, @Param("revokedAt") Instant revokedAt);
}
