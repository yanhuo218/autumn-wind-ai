package io.github.yanhuo218.autumnwind.identity.domain.policy;

import java.util.Objects;

public record RegistrationPolicy(
        boolean publicRegistrationEnabled,
        boolean emailVerificationRequired,
        EmailDomainPolicy emailDomainPolicy
) {

    public RegistrationPolicy {
        Objects.requireNonNull(emailDomainPolicy, "邮箱域策略不能为空。");
    }

    public boolean allowsPublicRegistration(NormalizedEmail email) {
        return publicRegistrationEnabled && emailDomainPolicy.allows(email);
    }
}
