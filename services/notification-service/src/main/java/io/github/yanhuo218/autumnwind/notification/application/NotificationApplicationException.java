package io.github.yanhuo218.autumnwind.notification.application;

import java.util.Objects;

public final class NotificationApplicationException extends RuntimeException {

    private final NotificationErrorCode code;

    public NotificationApplicationException(NotificationErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "通知错误码不能为空。");
    }

    public NotificationApplicationException(NotificationErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "通知错误码不能为空。");
    }

    public NotificationErrorCode code() {
        return code;
    }
}
