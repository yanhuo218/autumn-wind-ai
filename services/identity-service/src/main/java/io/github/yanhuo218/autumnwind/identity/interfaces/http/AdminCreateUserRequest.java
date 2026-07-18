package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import io.github.yanhuo218.autumnwind.identity.application.administration.AdminCreateUserCommand;
import io.github.yanhuo218.autumnwind.identity.domain.account.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminCreateUserRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 1024) String password,
        @NotBlank @Size(max = 80) String displayName,
        @NotNull UserRole role,
        Boolean emailVerified
) {

    public AdminCreateUserCommand toCommand() {
        return new AdminCreateUserCommand(email, password, displayName, role, Boolean.TRUE.equals(emailVerified));
    }
}
