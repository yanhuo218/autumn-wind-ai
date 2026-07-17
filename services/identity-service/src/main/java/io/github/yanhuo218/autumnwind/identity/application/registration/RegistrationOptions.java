package io.github.yanhuo218.autumnwind.identity.application.registration;

public record RegistrationOptions(
        boolean publicRegistrationEnabled,
        boolean emailVerificationRequired,
        int passwordMinimumLength,
        boolean termsAcceptanceRequired,
        boolean privacyAcceptanceRequired
) {
}
