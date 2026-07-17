package io.github.yanhuo218.autumnwind.identity.application.error;

public final class InvalidSessionException extends IdentityApplicationException {

    public InvalidSessionException() {
        super(IdentityErrorCode.INVALID_SESSION, "会话无效或已过期。");
    }
}
