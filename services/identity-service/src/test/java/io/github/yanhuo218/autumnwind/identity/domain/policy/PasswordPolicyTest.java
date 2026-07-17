package io.github.yanhuo218.autumnwind.identity.domain.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordPolicyTest {

    @Test
    void 按Unicode字符数校验密码长度() {
        PasswordPolicy policy = new PasswordPolicy(12, 128);

        assertTrue(policy.accepts("abcd1234测试测试"));
        assertFalse(policy.accepts("short"));
        assertFalse(policy.accepts(null));
    }

    @Test
    void 拒绝低于安全基线或倒置的长度策略() {
        assertThrows(IllegalArgumentException.class, () -> new PasswordPolicy(8, 128));
        assertThrows(IllegalArgumentException.class, () -> new PasswordPolicy(128, 64));
    }
}
