package io.github.yanhuo218.autumnwind.identity.domain.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountStatusTest {

    @Test
    void 只有活跃账户允许登录() {
        assertTrue(AccountStatus.ACTIVE.canLogin());
        assertFalse(AccountStatus.PENDING_VERIFICATION.canLogin());
        assertFalse(AccountStatus.DISABLED.canLogin());
        assertFalse(AccountStatus.DELETION_PENDING.canLogin());
        assertFalse(AccountStatus.DELETED.canLogin());
    }

    @Test
    void 删除流程只能按显式状态推进() {
        assertTrue(AccountStatus.ACTIVE.canTransitionTo(AccountStatus.DELETION_PENDING));
        assertTrue(AccountStatus.DELETION_PENDING.canTransitionTo(AccountStatus.DELETED));
        assertFalse(AccountStatus.ACTIVE.canTransitionTo(AccountStatus.DELETED));
        assertFalse(AccountStatus.DELETED.canTransitionTo(AccountStatus.ACTIVE));
    }

    @Test
    void 重新启用不会改变已删除账户() {
        assertTrue(AccountStatus.DISABLED.canTransitionTo(AccountStatus.ACTIVE));
        assertFalse(AccountStatus.DELETION_PENDING.canTransitionTo(AccountStatus.ACTIVE));
    }
}
