package io.github.yanhuo218.autumnwind.identity.application.administration;

import io.github.yanhuo218.autumnwind.identity.domain.account.UserRole;

public record AdminCreateUserCommand(
        String email,
        String password,
        String displayName,
        UserRole role,
        boolean emailVerified
) {

    @Override
    public String toString() {
        return "AdminCreateUserCommand[email=<REDACTED>, password=<REDACTED>, displayName=<REDACTED>, role="
                + role + ", emailVerified=" + emailVerified + "]";
    }
}
