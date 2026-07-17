package io.github.yanhuo218.autumnwind.identity.application.registration;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistrationServiceTest {

    private final RegistrationTransactionService transactionService = mock(RegistrationTransactionService.class);
    private final RegistrationService service = new RegistrationService(transactionService);
    private final RegisterCommand command = new RegisterCommand(
            "user@example.com", "Secure-Pass-123", "User", null, null
    );

    @Test
    void 数据库并发重复邮箱仍统一受理() {
        ConstraintViolationException constraint = mock(ConstraintViolationException.class);
        when(constraint.getSQLState()).thenReturn("23505");
        when(constraint.getConstraintName()).thenReturn("users_email_key");
        doThrow(new DataIntegrityViolationException("duplicate", constraint))
                .when(transactionService).create(command);

        assertEquals(RegistrationResult.ACCEPTED, service.register(command));
    }

    @Test
    void 不吞掉其他数据完整性错误() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("other constraint");
        doThrow(failure).when(transactionService).create(command);

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> service.register(command)
        );
        assertSame(failure, thrown);
    }
}
