package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import io.github.yanhuo218.autumnwind.identity.application.registration.RegisterCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 320) String email,
        @NotNull @Size(min = 1, max = 1024) String password,
        @NotBlank @Size(max = 80) String displayName,
        @Size(max = 64) String acceptedTermsVersion,
        @Size(max = 64) String acceptedPrivacyVersion
) {

    RegisterCommand toCommand() {
        return new RegisterCommand(
                email,
                password,
                displayName,
                acceptedTermsVersion,
                acceptedPrivacyVersion
        );
    }

    @Override
    public String toString() {
        return "RegisterRequest[email=<REDACTED>, password=<REDACTED>, displayName=<REDACTED>, "
                + "acceptedTermsVersion=<REDACTED>, acceptedPrivacyVersion=<REDACTED>]";
    }
}
