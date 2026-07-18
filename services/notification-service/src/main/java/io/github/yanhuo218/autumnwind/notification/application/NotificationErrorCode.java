package io.github.yanhuo218.autumnwind.notification.application;

public enum NotificationErrorCode {
    INVALID_REQUEST("AW-NOTIFICATION-VALIDATION-0001"),
    INVALID_SERVICE_TOKEN("AW-NOTIFICATION-AUTH-0001"),
    ACCESS_DENIED("AW-NOTIFICATION-FORBIDDEN-0001"),
    SMTP_CONFIG_NOT_FOUND("AW-NOTIFICATION-NOT_FOUND-0001"),
    SMTP_CONFIG_UNAVAILABLE("AW-NOTIFICATION-CONFLICT-0001"),
    SMTP_CONFIG_VERSION_CONFLICT("AW-NOTIFICATION-CONFLICT-0002"),
    SMTP_SECRET_FAILURE("AW-NOTIFICATION-DEPENDENCY-0001"),
    INTERNAL_ERROR("AW-NOTIFICATION-INTERNAL-0001");

    private final String value;

    NotificationErrorCode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
