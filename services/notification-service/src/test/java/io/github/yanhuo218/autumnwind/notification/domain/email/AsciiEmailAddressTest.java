package io.github.yanhuo218.autumnwind.notification.domain.email;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AsciiEmailAddressTest {

    @Test
    void 规范化域名并保留本地部分() {
        assertEquals("Sender+test@example.com", AsciiEmailAddress.normalize(" Sender+test@Example.COM "));
    }

    @Test
    void 拒绝空白连续点和非法域名() {
        assertThrows(IllegalArgumentException.class, () -> AsciiEmailAddress.normalize(" "));
        assertThrows(IllegalArgumentException.class, () -> AsciiEmailAddress.normalize("a..b@example.com"));
        assertThrows(IllegalArgumentException.class, () -> AsciiEmailAddress.normalize("sender@example"));
        assertThrows(IllegalArgumentException.class, () -> AsciiEmailAddress.normalize("sender@-example.com"));
    }
}
