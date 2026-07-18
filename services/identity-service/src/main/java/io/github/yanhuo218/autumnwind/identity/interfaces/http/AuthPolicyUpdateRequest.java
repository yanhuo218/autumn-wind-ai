package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import io.github.yanhuo218.autumnwind.identity.application.administration.AuthPolicyUpdateCommand;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.domain.policy.AuthPolicySettings;
import io.github.yanhuo218.autumnwind.identity.domain.policy.DomainPolicyMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record AuthPolicyUpdateRequest(
        @NotNull Boolean publicRegistrationEnabled,
        @NotNull Boolean emailVerificationRequired,
        @NotNull DomainPolicyMode emailDomainPolicyMode,
        @NotNull @Size(max = 500) Set<String> emailDomains,
        @Min(5) @Max(10080) Integer verificationValidityMinutes,
        @Min(1) @Max(86400) Integer verificationResendCooldownSeconds,
        @Min(1) @Max(100) Integer verificationFailureLimit,
        @Min(12) @Max(128) Integer passwordMinimumLength,
        @Min(1) @Max(100) Integer loginFailureLimit,
        @Min(1) @Max(1440) Integer loginLockMinutes,
        @NotNull Boolean termsAcceptanceRequired,
        @NotNull Boolean privacyAcceptanceRequired
) {

    public AuthPolicyUpdateCommand toCommand(long expectedVersion, UUID actorUserId) {
        try {
            return new AuthPolicyUpdateCommand(
                    expectedVersion,
                    new AuthPolicySettings(
                            publicRegistrationEnabled,
                            emailVerificationRequired,
                            emailDomainPolicyMode,
                            emailDomains,
                            verificationValidityMinutes,
                            verificationResendCooldownSeconds,
                            verificationFailureLimit,
                            passwordMinimumLength,
                            loginFailureLimit,
                            loginLockMinutes,
                            termsAcceptanceRequired,
                            privacyAcceptanceRequired
                    ),
                    actorUserId
            );
        } catch (IllegalArgumentException exception) {
            throw new IdentityApplicationException(IdentityErrorCode.INVALID_REQUEST, "认证策略字段不合法。");
        }
    }
}
