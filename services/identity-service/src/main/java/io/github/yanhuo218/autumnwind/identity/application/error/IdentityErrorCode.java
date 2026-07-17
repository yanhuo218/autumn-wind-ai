package io.github.yanhuo218.autumnwind.identity.application.error;

public enum IdentityErrorCode {
    INVALID_REQUEST("AW-IDENTITY-VALIDATION-0001"),
    REGISTRATION_NOT_ALLOWED("AW-IDENTITY-FORBIDDEN-0001"),
    AUTHENTICATION_FAILED("AW-IDENTITY-AUTH-0001"),
    INVALID_SESSION("AW-IDENTITY-AUTH-0002"),
    REGISTRATION_UNAVAILABLE("AW-IDENTITY-DEPENDENCY-0001"),
    POLICY_UNAVAILABLE("AW-IDENTITY-INTERNAL-0001");

    private final String value;

    IdentityErrorCode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
