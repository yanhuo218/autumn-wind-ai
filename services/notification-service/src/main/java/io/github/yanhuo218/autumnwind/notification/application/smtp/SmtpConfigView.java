package io.github.yanhuo218.autumnwind.notification.application.smtp;

import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpSecurityMode;
import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpSettings;
import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpTestStatus;
import io.github.yanhuo218.autumnwind.notification.infrastructure.persistence.SmtpConfigEntity;

import java.time.Instant;

public record SmtpConfigView(
        String host,
        int port,
        SmtpSecurityMode securityMode,
        String username,
        boolean passwordConfigured,
        String fromAddress,
        String fromName,
        SmtpTestStatus lastTestStatus,
        Instant lastTestedAt,
        long version,
        Instant updatedAt
) {

    public static SmtpConfigView from(SmtpConfigEntity entity) {
        SmtpSettings settings = entity.settings();
        return new SmtpConfigView(
                settings.host(),
                settings.port(),
                settings.securityMode(),
                settings.username(),
                entity.currentCredential() != null,
                settings.fromAddress(),
                settings.fromName(),
                entity.lastTestStatus(),
                entity.lastTestedAt(),
                entity.version(),
                entity.updatedAt()
        );
    }
}
