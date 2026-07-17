package io.github.yanhuo218.autumnwind.notification.infrastructure.persistence;

import io.github.yanhuo218.autumnwind.notification.domain.email.EmailJobStatus;
import io.github.yanhuo218.autumnwind.notification.domain.email.EmailPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "email_jobs", schema = "notification")
public class EmailJobEntity {

    @Id
    private UUID id;

    @Column(name = "delivery_request_id", nullable = false, unique = true)
    private UUID deliveryRequestId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "recipient_email", nullable = false, length = 320)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EmailPurpose purpose;

    @Column(name = "template_key", length = 64)
    private String templateKey;

    @Column(name = "content_reference_id")
    private UUID contentReferenceId;

    @Column(length = 16)
    private String locale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private EmailJobStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "smtp_config_version")
    private Long smtpConfigVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    protected EmailJobEntity() {
    }

    public static EmailJobEntity createSmtpTest(
            UUID id,
            UUID deliveryRequestId,
            UUID actorUserId,
            String recipientEmail,
            String correlationId,
            long smtpConfigVersion,
            Instant now
    ) {
        if (smtpConfigVersion < 0) {
            throw new IllegalArgumentException("被测试的 SMTP 配置版本不能为负数。");
        }
        EmailJobEntity entity = new EmailJobEntity();
        entity.id = Objects.requireNonNull(id, "邮件任务标识不能为空。");
        entity.deliveryRequestId = Objects.requireNonNull(deliveryRequestId, "投递请求标识不能为空。");
        entity.userId = Objects.requireNonNull(actorUserId, "操作者标识不能为空。");
        entity.recipientEmail = Objects.requireNonNull(recipientEmail, "收件地址不能为空。");
        entity.purpose = EmailPurpose.SMTP_TEST;
        entity.status = EmailJobStatus.QUEUED;
        entity.smtpConfigVersion = smtpConfigVersion;
        entity.correlationId = requireCorrelationId(correlationId);
        entity.createdAt = Objects.requireNonNull(now, "任务创建时间不能为空。");
        return entity;
    }

    private static String requireCorrelationId(String value) {
        if (value == null || value.isBlank() || value.length() > 64
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("关联标识格式不合法。");
        }
        return value;
    }

    public UUID id() {
        return id;
    }

    public String recipientEmail() {
        return recipientEmail;
    }

    public EmailPurpose purpose() {
        return purpose;
    }

    public EmailJobStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Long smtpConfigVersion() {
        return smtpConfigVersion;
    }
}
