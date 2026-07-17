package io.github.yanhuo218.autumnwind.identity.domain.policy;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailDomainPolicyTest {

    @Test
    void 白名单模式默认拒绝未列出的完整域名() {
        EmailDomainPolicy policy = new EmailDomainPolicy(
                DomainPolicyMode.ALLOWLIST,
                Set.of("Example.COM.")
        );

        assertTrue(policy.allows(EmailAddressNormalizer.normalizeEmail("user@example.com")));
        assertFalse(policy.allows(EmailAddressNormalizer.normalizeEmail("user@sub.example.com")));
    }

    @Test
    void 黑名单模式默认允许未列出的完整域名() {
        EmailDomainPolicy policy = new EmailDomainPolicy(
                DomainPolicyMode.BLOCKLIST,
                Set.of("blocked.example")
        );

        assertFalse(policy.allows(EmailAddressNormalizer.normalizeEmail("user@blocked.example")));
        assertTrue(policy.allows(EmailAddressNormalizer.normalizeEmail("user@allowed.example")));
    }
}
