package io.github.yanhuo218.autumnwind.identity.application.registration;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class RegistrationService {

    private static final String EMAIL_UNIQUE_CONSTRAINT = "users_email_key";

    private final RegistrationTransactionService transactionService;

    public RegistrationService(RegistrationTransactionService transactionService) {
        this.transactionService = Objects.requireNonNull(transactionService, "注册事务服务不能为空。");
    }

    public RegistrationResult register(RegisterCommand command) {
        try {
            transactionService.create(command);
            return RegistrationResult.ACCEPTED;
        }
        catch (DataIntegrityViolationException exception) {
            if (isDuplicateEmail(exception)) {
                return RegistrationResult.ACCEPTED;
            }
            throw exception;
        }
    }

    private static boolean isDuplicateEmail(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraintViolation
                    && "23505".equals(constraintViolation.getSQLState())
                    && constraintViolation.getConstraintName() != null
                    && constraintViolation.getConstraintName().endsWith(EMAIL_UNIQUE_CONSTRAINT)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
