package io.github.yanhuo218.autumnwind.notification.application.smtp;

import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpSettings;

import java.util.Objects;
import java.util.UUID;

public record SmtpConfigUpdateCommand(
        SmtpSettings settings,
        String password,
        boolean clearPassword,
        long expectedVersion,
        UUID actorUserId
) {

    public SmtpConfigUpdateCommand {
        Objects.requireNonNull(settings, "SMTP 设置不能为空。");
        Objects.requireNonNull(actorUserId, "操作者标识不能为空。");
        if (password != null && (password.isEmpty() || password.length() > 1024)) {
            throw new IllegalArgumentException("SMTP 密码长度必须在 1 到 1024 之间。");
        }
        if (password != null && clearPassword) {
            throw new IllegalArgumentException("SMTP 密码与清除密码操作不能同时提交。");
        }
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("SMTP 配置版本不能为负数。");
        }
    }

    public boolean hasPassword() {
        return password != null;
    }

    @Override
    public String toString() {
        return "SmtpConfigUpdateCommand[settings=" + settings
                + ", password=<REDACTED>, clearPassword=" + clearPassword
                + ", expectedVersion=" + expectedVersion + ", actorUserId=" + actorUserId + "]";
    }
}
