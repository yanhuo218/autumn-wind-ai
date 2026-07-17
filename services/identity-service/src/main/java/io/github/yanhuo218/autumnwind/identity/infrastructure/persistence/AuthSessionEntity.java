package io.github.yanhuo218.autumnwind.identity.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "auth_sessions", schema = "identity")
public class AuthSessionEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected AuthSessionEntity() {
    }

    public AuthSessionEntity(
            UUID id,
            UUID userId,
            String tokenHash,
            Instant expiresAt,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "会话标识不能为空。");
        this.userId = Objects.requireNonNull(userId, "用户标识不能为空。");
        this.tokenHash = Objects.requireNonNull(tokenHash, "会话 Token Hash 不能为空。");
        this.expiresAt = Objects.requireNonNull(expiresAt, "会话过期时间不能为空。");
        this.createdAt = Objects.requireNonNull(createdAt, "会话创建时间不能为空。");
        this.lastSeenAt = createdAt;
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void markSeen(Instant now) {
        lastSeenAt = now;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
