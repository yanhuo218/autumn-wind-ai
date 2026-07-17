package io.github.yanhuo218.autumnwind.identity.domain.policy;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationPolicyTest {

    private final NormalizedEmail allowedEmail = EmailAddressNormalizer.normalizeEmail("user@example.com");
    private final EmailDomainPolicy allowlist = new EmailDomainPolicy(
            DomainPolicyMode.ALLOWLIST,
            Set.of("example.com")
    );

    @Test
    void 关闭公开注册后拒绝访客注册() {
        RegistrationPolicy policy = new RegistrationPolicy(false, false, allowlist);

        assertFalse(policy.allowsPublicRegistration(allowedEmail));
    }

    @Test
    void 开启公开注册后仍执行邮箱域策略() {
        RegistrationPolicy policy = new RegistrationPolicy(true, true, allowlist);

        assertTrue(policy.allowsPublicRegistration(allowedEmail));
        assertFalse(policy.allowsPublicRegistration(
                EmailAddressNormalizer.normalizeEmail("user@other.example")
        ));
        assertTrue(policy.emailVerificationRequired());
    }
}
