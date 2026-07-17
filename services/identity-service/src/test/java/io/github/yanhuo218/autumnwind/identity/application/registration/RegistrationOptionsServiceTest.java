package io.github.yanhuo218.autumnwind.identity.application.registration;

import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.domain.policy.DomainPolicyMode;
import io.github.yanhuo218.autumnwind.identity.domain.policy.EmailDomainPolicy;
import io.github.yanhuo218.autumnwind.identity.domain.policy.PasswordPolicy;
import io.github.yanhuo218.autumnwind.identity.domain.policy.RegistrationPolicy;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistrationOptionsServiceTest {

    private final AuthPolicyRepository repository = mock(AuthPolicyRepository.class);
    private final RegistrationOptionsService service = new RegistrationOptionsService(repository);

    @Test
    void 只返回注册页面需要的公开策略() {
        AuthPolicyEntity policy = mock(AuthPolicyEntity.class);
        when(repository.findById((short) 1)).thenReturn(Optional.of(policy));
        when(policy.registrationPolicy()).thenReturn(new RegistrationPolicy(
                true,
                false,
                new EmailDomainPolicy(DomainPolicyMode.BLOCKLIST, Set.of("blocked.example"))
        ));
        when(policy.passwordPolicy()).thenReturn(new PasswordPolicy(16, 128));
        when(policy.termsAcceptanceRequired()).thenReturn(true);
        when(policy.privacyAcceptanceRequired()).thenReturn(false);

        RegistrationOptions options = service.getOptions();

        assertTrue(options.publicRegistrationEnabled());
        assertFalse(options.emailVerificationRequired());
        assertEquals(16, options.passwordMinimumLength());
        assertTrue(options.termsAcceptanceRequired());
        assertFalse(options.privacyAcceptanceRequired());
    }

    @Test
    void 缺少认证策略时返回稳定内部错误() {
        when(repository.findById((short) 1)).thenReturn(Optional.empty());

        IdentityApplicationException exception = assertThrows(
                IdentityApplicationException.class,
                service::getOptions
        );

        assertEquals(IdentityErrorCode.POLICY_UNAVAILABLE, exception.errorCode());
    }
}
