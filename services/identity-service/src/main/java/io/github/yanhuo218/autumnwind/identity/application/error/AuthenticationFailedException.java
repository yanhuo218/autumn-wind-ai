package io.github.yanhuo218.autumnwind.identity.application.error;

public final class AuthenticationFailedException extends IdentityApplicationException {

    public AuthenticationFailedException() {
        super(IdentityErrorCode.AUTHENTICATION_FAILED, "邮箱或密码不正确。");
    }
}
