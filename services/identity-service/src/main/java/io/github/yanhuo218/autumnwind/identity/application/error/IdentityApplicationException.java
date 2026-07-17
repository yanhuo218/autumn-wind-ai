package io.github.yanhuo218.autumnwind.identity.application.error;

import java.util.Objects;

public class IdentityApplicationException extends RuntimeException {

    private final IdentityErrorCode errorCode;

    public IdentityApplicationException(IdentityErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "错误码不能为空。");
    }

    public IdentityErrorCode errorCode() {
        return errorCode;
    }
}
