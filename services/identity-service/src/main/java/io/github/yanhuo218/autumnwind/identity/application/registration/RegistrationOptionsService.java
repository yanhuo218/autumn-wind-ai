package io.github.yanhuo218.autumnwind.identity.application.registration;

import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.domain.policy.PasswordPolicy;
import io.github.yanhuo218.autumnwind.identity.domain.policy.RegistrationPolicy;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class RegistrationOptionsService {

    private static final short POLICY_ID = 1;

    private final AuthPolicyRepository authPolicyRepository;

    public RegistrationOptionsService(AuthPolicyRepository authPolicyRepository) {
        this.authPolicyRepository = Objects.requireNonNull(authPolicyRepository, "认证策略仓库不能为空。");
    }

    @Transactional(readOnly = true)
    public RegistrationOptions getOptions() {
        AuthPolicyEntity policy = authPolicyRepository.findById(POLICY_ID)
                .orElseThrow(RegistrationOptionsService::policyUnavailable);
        RegistrationPolicy registrationPolicy = policy.registrationPolicy();
        PasswordPolicy passwordPolicy = policy.passwordPolicy();
        return new RegistrationOptions(
                registrationPolicy.publicRegistrationEnabled(),
                registrationPolicy.emailVerificationRequired(),
                passwordPolicy.minimumLength(),
                policy.termsAcceptanceRequired(),
                policy.privacyAcceptanceRequired()
        );
    }

    private static IdentityApplicationException policyUnavailable() {
        return new IdentityApplicationException(IdentityErrorCode.POLICY_UNAVAILABLE, "认证策略不可用。");
    }
}
