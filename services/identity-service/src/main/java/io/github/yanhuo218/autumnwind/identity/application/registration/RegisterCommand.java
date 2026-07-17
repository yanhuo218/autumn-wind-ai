package io.github.yanhuo218.autumnwind.identity.application.registration;

public record RegisterCommand(
        String email,
        String password,
        String displayName,
        String acceptedTermsVersion,
        String acceptedPrivacyVersion
) {

    @Override
    public String toString() {
        return "RegisterCommand[email=<REDACTED>, password=<REDACTED>, displayName=<REDACTED>, "
                + "acceptedTermsVersion=<REDACTED>, acceptedPrivacyVersion=<REDACTED>]";
    }
}
