package io.github.yanhuo218.autumnwind.identity.infrastructure.persistence;

import io.github.yanhuo218.autumnwind.identity.domain.account.AccountStatus;
import io.github.yanhuo218.autumnwind.identity.domain.account.UserRole;
import io.github.yanhuo218.autumnwind.identity.domain.security.PasswordHasher;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "users", schema = "identity")
public class UserEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 512)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccountStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected UserEntity() {
    }

    private UserEntity(
            UUID id,
            String email,
            String passwordHash,
            String displayName,
            AccountStatus status,
            UserRole role,
            Instant now
    ) {
        this.id = Objects.requireNonNull(id, "用户标识不能为空。");
        this.email = Objects.requireNonNull(email, "邮箱不能为空。");
        this.passwordHash = Objects.requireNonNull(passwordHash, "密码 Hash 不能为空。");
        this.displayName = Objects.requireNonNull(displayName, "显示名称不能为空。");
        this.status = Objects.requireNonNull(status, "账户状态不能为空。");
        this.role = Objects.requireNonNull(role, "用户角色不能为空。");
        this.createdAt = Objects.requireNonNull(now, "创建时间不能为空。");
        this.updatedAt = now;
    }

    public static UserEntity register(
            UUID id,
            String email,
            String passwordHash,
            String displayName,
            boolean verificationRequired,
            Instant now
    ) {
        AccountStatus status = verificationRequired
                ? AccountStatus.PENDING_VERIFICATION
                : AccountStatus.ACTIVE;
        return new UserEntity(id, email, passwordHash, displayName, status, UserRole.USER, now);
    }

    public boolean passwordMatches(PasswordHasher hasher, CharSequence password) {
        return hasher.matches(password, passwordHash);
    }

    public boolean isLoginLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void recordFailedLogin(int failureLimit, Duration lockDuration, Instant now) {
        if (lockedUntil != null && !lockedUntil.isAfter(now)) {
            failedLoginCount = 0;
            lockedUntil = null;
        }
        failedLoginCount++;
        if (failedLoginCount >= failureLimit) {
            lockedUntil = now.plus(lockDuration);
        }
        updatedAt = now;
    }

    public void recordSuccessfulLogin(Instant now) {
        failedLoginCount = 0;
        lockedUntil = null;
        lastLoginAt = now;
        updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public AccountStatus status() {
        return status;
    }

    public UserRole role() {
        return role;
    }

    public Instant lastLoginAt() {
        return lastLoginAt;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
