package io.github.yanhuo218.autumnwind.notification.infrastructure.persistence;

import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpSecurityMode;
import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpSettings;
import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpTestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "smtp_config", schema = "notification")
public class SmtpConfigEntity {

    public static final short SINGLETON_ID = 1;

    @Id
    private short id;

    @Column(nullable = false, length = 253)
    private String host;

    @Column(nullable = false)
    private int port;

    @Enumerated(EnumType.STRING)
    @Column(name = "security_mode", nullable = false, length = 16)
    private SmtpSecurityMode securityMode;

    @Column(length = 320)
    private String username;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_credential_id")
    private SmtpCredentialEntity currentCredential;

    @Column(name = "from_address", nullable = false, length = 320)
    private String fromAddress;

    @Column(name = "from_name", nullable = false, length = 200)
    private String fromName;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_test_status", nullable = false, length = 16)
    private SmtpTestStatus lastTestStatus;

    @Column(name = "last_tested_at")
    private Instant lastTestedAt;

    @Column(name = "last_test_job_id")
    private UUID lastTestJobId;

    @Column(name = "last_test_config_version")
    private Long lastTestConfigVersion;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    protected SmtpConfigEntity() {
    }

    public static SmtpConfigEntity create(
            SmtpSettings settings,
            SmtpCredentialEntity credential,
            UUID actorUserId,
            Instant now
    ) {
        SmtpConfigEntity entity = new SmtpConfigEntity();
        entity.id = SINGLETON_ID;
        entity.lastTestStatus = SmtpTestStatus.NEVER;
        entity.apply(settings, credential, actorUserId, now);
        return entity;
    }

    public void update(
            SmtpSettings settings,
            SmtpCredentialEntity credential,
            UUID actorUserId,
            Instant now
    ) {
        lastTestStatus = SmtpTestStatus.NEVER;
        lastTestedAt = null;
        lastTestJobId = null;
        lastTestConfigVersion = null;
        apply(settings, credential, actorUserId, now);
    }

    public void markTestQueued(UUID jobId, long testedConfigVersion, UUID actorUserId, Instant now) {
        if (testedConfigVersion < 0) {
            throw new IllegalArgumentException("被测试的 SMTP 配置版本不能为负数。");
        }
        lastTestStatus = SmtpTestStatus.QUEUED;
        lastTestJobId = Objects.requireNonNull(jobId, "测试邮件任务标识不能为空。");
        lastTestConfigVersion = testedConfigVersion;
        updatedBy = Objects.requireNonNull(actorUserId, "操作者标识不能为空。");
        updatedAt = Objects.requireNonNull(now, "更新时间不能为空。");
    }

    private void apply(
            SmtpSettings settings,
            SmtpCredentialEntity credential,
            UUID actorUserId,
            Instant now
    ) {
        Objects.requireNonNull(settings, "SMTP 设置不能为空。");
        host = settings.host();
        port = settings.port();
        securityMode = settings.securityMode();
        username = settings.username();
        currentCredential = credential;
        fromAddress = settings.fromAddress();
        fromName = settings.fromName();
        updatedBy = Objects.requireNonNull(actorUserId, "操作者标识不能为空。");
        updatedAt = Objects.requireNonNull(now, "更新时间不能为空。");
    }

    public SmtpSettings settings() {
        return new SmtpSettings(host, port, securityMode, username, fromAddress, fromName);
    }

    public SmtpCredentialEntity currentCredential() {
        return currentCredential;
    }

    public SmtpTestStatus lastTestStatus() {
        return lastTestStatus;
    }

    public Instant lastTestedAt() {
        return lastTestedAt;
    }

    public UUID lastTestJobId() {
        return lastTestJobId;
    }

    public Long lastTestConfigVersion() {
        return lastTestConfigVersion;
    }

    public long version() {
        return version;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
