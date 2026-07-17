package io.github.yanhuo218.autumnwind.identity.domain.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmailAddressNormalizerTest {

    @Test
    void 规范化邮箱大小写末尾点和国际化域名() {
        NormalizedEmail email = EmailAddressNormalizer.normalizeEmail(" User@例子.测试. ");

        assertEquals("user@xn--fsqu00a.xn--0zwm56d", email.value());
        assertEquals("xn--fsqu00a.xn--0zwm56d", email.domain());
    }

    @Test
    void 拒绝缺少有效域名的邮箱() {
        assertThrows(IllegalArgumentException.class,
                () -> EmailAddressNormalizer.normalizeEmail("user@localhost"));
        assertThrows(IllegalArgumentException.class,
                () -> EmailAddressNormalizer.normalizeEmail("user@@example.com"));
    }
}
