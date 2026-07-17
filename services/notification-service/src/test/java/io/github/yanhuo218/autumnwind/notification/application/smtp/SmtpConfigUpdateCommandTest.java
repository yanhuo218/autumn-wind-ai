package io.github.yanhuo218.autumnwind.notification.application.smtp;

import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpSecurityMode;
import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpSettings;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SmtpConfigUpdateCommandTest {

    @Test
    void 拒绝同时替换与清除密码() {
        assertThrows(IllegalArgumentException.class, () -> new SmtpConfigUpdateCommand(
                settings(),
                "not-a-real-password",
                true,
                0,
                UUID.randomUUID()
        ));
    }

    @Test
    void 字符串表示不包含密码() {
        SmtpConfigUpdateCommand command = new SmtpConfigUpdateCommand(
                settings(),
                "not-a-real-password",
                false,
                0,
                UUID.randomUUID()
        );

        assertFalse(command.toString().contains("not-a-real-password"));
    }

    @Test
    void 测试邮件命令字符串不包含完整收件地址() {
        TestEmailCommand command = new TestEmailCommand(
                "recipient@example.com",
                0,
                UUID.randomUUID(),
                "correlation-1"
        );

        assertFalse(command.toString().contains("recipient@example.com"));
    }

    @Test
    void 拒绝非法密码版本和关联标识() {
        assertThrows(IllegalArgumentException.class, () -> new SmtpConfigUpdateCommand(
                settings(), "", false, 0, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> new SmtpConfigUpdateCommand(
                settings(), null, false, -1, UUID.randomUUID()));
        assertThrows(NullPointerException.class, () -> new SmtpConfigUpdateCommand(
                settings(), null, false, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new TestEmailCommand(
                "recipient@example.com", -1, UUID.randomUUID(), "correlation-1"));
        assertThrows(NullPointerException.class, () -> new TestEmailCommand(
                "recipient@example.com", 0, null, "correlation-1"));
        assertThrows(IllegalArgumentException.class, () -> new TestEmailCommand(
                "recipient@example.com", 0, UUID.randomUUID(), "bad\ncorrelation"));
    }

    private static SmtpSettings settings() {
        return new SmtpSettings(
                "smtp.example.com",
                587,
                SmtpSecurityMode.STARTTLS,
                "user",
                "sender@example.com",
                "Autumn Wind Ai"
        );
    }
}
