package io.github.yanhuo218.autumnwind.notification.interfaces.http;

import io.github.yanhuo218.autumnwind.notification.application.smtp.TestEmailCommand;

import java.util.UUID;

public final class TestEmailRequest {

    private String recipientEmail;
    private Long expectedConfigVersion;

    public void setRecipientEmail(String recipientEmail) {
        this.recipientEmail = recipientEmail;
    }

    public void setExpectedConfigVersion(Long expectedConfigVersion) {
        this.expectedConfigVersion = expectedConfigVersion;
    }

    public TestEmailCommand toCommand(UUID actorUserId, String correlationId) {
        return new TestEmailCommand(
                recipientEmail,
                requirePresent(expectedConfigVersion, "SMTP 配置预期版本不能为空。"),
                actorUserId,
                correlationId
        );
    }

    private static <T> T requirePresent(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    @Override
    public String toString() {
        return "TestEmailRequest[recipientEmail=<REDACTED>, expectedConfigVersion="
                + expectedConfigVersion + "]";
    }
}
