package io.github.yanhuo218.autumnwind.identity.application.administration;

import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.domain.account.UserRole;
import io.github.yanhuo218.autumnwind.identity.domain.policy.PasswordPolicy;
import io.github.yanhuo218.autumnwind.identity.domain.security.PasswordHasher;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyRepository;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserCreationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T09:30:00Z");
    private static final String PASSWORD = "Secure-Pass-123";

    private final AuthPolicyRepository policyRepository = mock(AuthPolicyRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final AuthPolicyEntity policy = mock(AuthPolicyEntity.class);
    private final AdminUserCreationService service = new AdminUserCreationService(
            policyRepository,
            userRepository,
            passwordHasher,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @BeforeEach
    void setUp() {
        when(policyRepository.findById((short) 1)).thenReturn(Optional.of(policy));
        when(policy.passwordPolicy()).thenReturn(new PasswordPolicy(12, 128));
        when(passwordHasher.hash(PASSWORD)).thenReturn("encoded-password");
    }

    @Test
    void 管理员创建用户会规范化输入并保存指定角色() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);

        UserAdminView view = service.create(new AdminCreateUserCommand(
                " User@Example.COM. ", PASSWORD, " User ", UserRole.ADMIN, true
        ));

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(captor.capture());
        UserEntity saved = captor.getValue();
        assertEquals("user@example.com", saved.email());
        assertEquals("User", saved.displayName());
        assertEquals(UserRole.ADMIN, saved.role());
        assertEquals("user@example.com", view.email());
    }

    @Test
    void 重复邮箱返回冲突且不保存新用户() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        IdentityApplicationException exception = assertThrows(
                IdentityApplicationException.class,
                () -> service.create(new AdminCreateUserCommand(
                        "user@example.com", PASSWORD, "User", UserRole.USER, false
                ))
        );

        assertEquals(IdentityErrorCode.USER_CONFLICT, exception.errorCode());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void 弱密码被当前认证策略拒绝且不执行Hash() {
        when(policy.passwordPolicy()).thenReturn(new PasswordPolicy(16, 128));

        IdentityApplicationException exception = assertThrows(
                IdentityApplicationException.class,
                () -> service.create(new AdminCreateUserCommand(
                        "user@example.com", PASSWORD, "User", UserRole.USER, false
                ))
        );

        assertEquals(IdentityErrorCode.INVALID_REQUEST, exception.errorCode());
        verify(passwordHasher, never()).hash(any());
    }
}
