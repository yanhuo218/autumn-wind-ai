package io.github.yanhuo218.autumnwind.identity.domain.policy;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthPolicySettingsTest {

    @Test
    void 邮箱域会标准化且时间配置会转换为秒() {
        AuthPolicySettings settings = new AuthPolicySettings(
                true,
                false,
                DomainPolicyMode.BLOCKLIST,
                Set.of(" Example.COM "),
                60,
                30,
                5,
                12,
                5,
                15,
                false,
                false
        );

        assertEquals(Set.of("example.com"), settings.emailDomains());
        assertEquals(3600, settings.verificationTtlSeconds());
        assertEquals(900, settings.loginLockDurationSeconds());
    }

    @Test
    void 拒绝超过持久化约束的锁定时长和邮箱域数量() {
        assertThrows(IllegalArgumentException.class, () -> settings(1441, Set.of()));

        Set<String> tooManyDomains = java.util.stream.IntStream.range(0, 501)
                .mapToObj(index -> "domain" + index + ".example")
                .collect(java.util.stream.Collectors.toSet());
        assertThrows(IllegalArgumentException.class, () -> settings(15, tooManyDomains));
    }

    private static AuthPolicySettings settings(int lockMinutes, Set<String> domains) {
        return new AuthPolicySettings(
                true,
                false,
                DomainPolicyMode.BLOCKLIST,
                domains,
                60,
                30,
                5,
                12,
                5,
                lockMinutes,
                false,
                false
        );
    }
}
