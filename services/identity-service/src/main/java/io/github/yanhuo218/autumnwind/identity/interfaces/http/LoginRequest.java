package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import io.github.yanhuo218.autumnwind.identity.application.session.LoginCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 320) String email,
        @NotNull @Size(min = 1, max = 1024) String password
) {

    LoginCommand toCommand() {
        return new LoginCommand(email, password);
    }

    @Override
    public String toString() {
        return "LoginRequest[email=<REDACTED>, password=<REDACTED>]";
    }
}
