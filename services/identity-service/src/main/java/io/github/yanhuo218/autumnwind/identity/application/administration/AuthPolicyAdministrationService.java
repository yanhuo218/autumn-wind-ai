package io.github.yanhuo218.autumnwind.identity.application.administration;

import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.domain.policy.AuthPolicySettings;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Service
public class AuthPolicyAdministrationService {

    private static final short POLICY_ID = 1;

    private final AuthPolicyRepository repository;
    private final Clock clock;

    public AuthPolicyAdministrationService(AuthPolicyRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "认证策略仓库不能为空。");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空。");
    }

    @Transactional(readOnly = true)
    public AuthPolicyView getPolicy() {
        return toView(policy());
    }

    @Transactional
    public AuthPolicyView updatePolicy(AuthPolicyUpdateCommand command) {
        AuthPolicyEntity entity = policy();
        if (entity.externalVersion() != command.expectedVersion()) {
            throw new IdentityApplicationException(IdentityErrorCode.VERSION_CONFLICT, "认证策略已被其他管理员更新。");
        }
        try {
            entity.apply(command.settings(), command.actorUserId(), Instant.now(clock));
            repository.saveAndFlush(entity);
            return toView(entity);
        } catch (OptimisticLockingFailureException exception) {
            throw new IdentityApplicationException(IdentityErrorCode.VERSION_CONFLICT, "认证策略已被其他管理员更新。");
        }
    }

    private AuthPolicyEntity policy() {
        return repository.findById(POLICY_ID)
                .orElseThrow(() -> new IdentityApplicationException(
                        IdentityErrorCode.POLICY_UNAVAILABLE,
                        "认证策略不可用。"
                ));
    }

    private static AuthPolicyView toView(AuthPolicyEntity entity) {
        AuthPolicySettings settings = entity.settings();
        return new AuthPolicyView(
                settings.publicRegistrationEnabled(),
                settings.emailVerificationRequired(),
                settings.emailDomainPolicyMode(),
                settings.emailDomains(),
                settings.verificationValidityMinutes(),
                settings.verificationResendCooldownSeconds(),
                settings.verificationFailureLimit(),
                settings.passwordMinimumLength(),
                settings.loginFailureLimit(),
                settings.loginLockMinutes(),
                settings.termsAcceptanceRequired(),
                settings.privacyAcceptanceRequired(),
                entity.externalVersion(),
                entity.updatedAt(),
                entity.updatedBy()
        );
    }
}
