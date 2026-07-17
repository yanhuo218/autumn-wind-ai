package io.github.yanhuo218.autumnwind.identity.infrastructure.persistence;

import io.github.yanhuo218.autumnwind.identity.domain.account.AccountStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserEntityTest {

    @Test
    void 注册策略决定初始账户状态() {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");

        UserEntity active = UserEntity.register(
                UUID.randomUUID(), "active@example.com", "hash", "Active", false, now
        );
        UserEntity pending = UserEntity.register(
                UUID.randomUUID(), "pending@example.com", "hash", "Pending", true, now
        );

        assertTrue(active.status() == AccountStatus.ACTIVE);
        assertTrue(pending.status() == AccountStatus.PENDING_VERIFICATION);
    }

    @Test
    void 登录失败达到阈值后临时锁定且成功后清除() {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        UserEntity user = UserEntity.register(
                UUID.randomUUID(), "user@example.com", "hash", "User", false, now
        );

        user.recordFailedLogin(2, Duration.ofMinutes(15), now);
        assertFalse(user.isLoginLocked(now));
        user.recordFailedLogin(2, Duration.ofMinutes(15), now);
        assertTrue(user.isLoginLocked(now.plusSeconds(1)));

        user.recordSuccessfulLogin(now.plusSeconds(2));
        assertFalse(user.isLoginLocked(now.plusSeconds(3)));
    }
}
