package io.github.yanhuo218.autumnwind.identity.application.administration;

import io.github.yanhuo218.autumnwind.identity.domain.policy.DomainPolicyMode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record AuthPolicyView(
        boolean publicRegistrationEnabled,
        boolean emailVerificationRequired,
        DomainPolicyMode emailDomainPolicyMode,
        Set<String> emailDomains,
        int verificationValidityMinutes,
        int verificationResendCooldownSeconds,
        int verificationFailureLimit,
        int passwordMinimumLength,
        int loginFailureLimit,
        int loginLockMinutes,
        boolean termsAcceptanceRequired,
        boolean privacyAcceptanceRequired,
        long version,
        Instant updatedAt,
        UUID updatedBy
) {
}
