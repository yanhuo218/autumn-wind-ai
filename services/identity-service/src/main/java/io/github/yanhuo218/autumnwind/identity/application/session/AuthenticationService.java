package io.github.yanhuo218.autumnwind.identity.application.session;

import io.github.yanhuo218.autumnwind.identity.application.error.AuthenticationFailedException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class AuthenticationService {

    private static final int MAX_ATTEMPTS = 3;

    private final AuthenticationTransactionService transactionService;

    public AuthenticationService(AuthenticationTransactionService transactionService) {
        this.transactionService = Objects.requireNonNull(transactionService, "认证事务服务不能为空。");
    }

    public LoginResult login(LoginCommand command) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transactionService.login(command);
            }
            catch (OptimisticLockingFailureException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new AuthenticationFailedException();
                }
            }
        }
        throw new AuthenticationFailedException();
    }
}
