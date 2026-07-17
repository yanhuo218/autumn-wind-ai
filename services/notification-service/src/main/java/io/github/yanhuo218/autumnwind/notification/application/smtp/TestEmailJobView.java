package io.github.yanhuo218.autumnwind.notification.application.smtp;

import io.github.yanhuo218.autumnwind.notification.domain.email.EmailJobStatus;
import io.github.yanhuo218.autumnwind.notification.infrastructure.persistence.EmailJobEntity;

import java.time.Instant;
import java.util.UUID;

public record TestEmailJobView(
        UUID jobId,
        EmailJobStatus status,
        Instant createdAt
) {

    public static TestEmailJobView from(EmailJobEntity entity) {
        return new TestEmailJobView(entity.id(), entity.status(), entity.createdAt());
    }
}
