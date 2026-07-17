package io.github.yanhuo218.autumnwind.identity.application.session;

import io.github.yanhuo218.autumnwind.identity.application.error.AuthenticationFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticationServiceTest {

    private final AuthenticationTransactionService transactionService = mock(AuthenticationTransactionService.class);
    private final AuthenticationService service = new AuthenticationService(transactionService);
    private final LoginCommand command = new LoginCommand("user@example.com", "password");

    @Test
    void 乐观锁冲突后有限重试并返回成功结果() {
        LoginResult expected = mock(LoginResult.class);
        when(transactionService.login(command))
                .thenThrow(new OptimisticLockingFailureException("conflict"))
                .thenReturn(expected);

        assertSame(expected, service.login(command));
        verify(transactionService, org.mockito.Mockito.times(2)).login(command);
    }

    @Test
    void 三次乐观锁冲突后返回通用认证失败() {
        when(transactionService.login(command))
                .thenThrow(new OptimisticLockingFailureException("conflict"));

        assertThrows(AuthenticationFailedException.class, () -> service.login(command));
        verify(transactionService, org.mockito.Mockito.times(3)).login(command);
    }
}
