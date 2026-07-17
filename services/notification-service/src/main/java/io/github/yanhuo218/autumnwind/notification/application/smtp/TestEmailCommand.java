package io.github.yanhuo218.autumnwind.notification.application.smtp;

import io.github.yanhuo218.autumnwind.notification.domain.email.AsciiEmailAddress;

import java.util.Objects;
import java.util.UUID;

public record TestEmailCommand(
        String recipientEmail,
        long expectedConfigVersion,
        UUID actorUserId,
        String correlationId
) {

    public TestEmailCommand {
        recipientEmail = AsciiEmailAddress.normalize(recipientEmail);
        Objects.requireNonNull(actorUserId, "操作者标识不能为空。");
        if (expectedConfigVersion < 0) {
            throw new IllegalArgumentException("SMTP 配置版本不能为负数。");
        }
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 64
                || correlationId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("关联标识格式不合法。");
        }
    }

    @Override
    public String toString() {
        return "TestEmailCommand[recipientEmail=<REDACTED>, expectedConfigVersion=" + expectedConfigVersion
                + ", actorUserId=" + actorUserId + ", correlationId=" + correlationId + "]";
    }
}
