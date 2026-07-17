package io.github.yanhuo218.autumnwind.identity.application.registration;

import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.domain.account.AccountStatus;
import io.github.yanhuo218.autumnwind.identity.domain.policy.DomainPolicyMode;
import io.github.yanhuo218.autumnwind.identity.domain.policy.EmailDomainPolicy;
import io.github.yanhuo218.autumnwind.identity.domain.policy.PasswordPolicy;
import io.github.yanhuo218.autumnwind.identity.domain.policy.RegistrationPolicy;
import io.github.yanhuo218.autumnwind.identity.domain.security.PasswordHasher;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyRepository;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private static final String PASSWORD = "Secure-Pass-123";

    @Mock
    private AuthPolicyRepository authPolicyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private AuthPolicyEntity authPolicy;

    private RegistrationTransactionService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationTransactionService(
                authPolicyRepository,
                userRepository,
                passwordHasher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        when(authPolicyRepository.findById((short) 1)).thenReturn(Optional.of(authPolicy));
    }

    @Test
    void 注册成功时规范化输入并只保存密码Hash() {
        enableRegistration(false, false, Set.of());
        when(authPolicy.passwordPolicy()).thenReturn(new PasswordPolicy(12, 128));
        when(passwordHasher.hash(PASSWORD)).thenReturn("encoded-password");

        service.create(new RegisterCommand(
                "  User@Example.COM. ", PASSWORD, "  测试用户  ", null, null
        ));

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(captor.capture());
        UserEntity saved = captor.getValue();
        assertEquals("user@example.com", saved.email());
        assertEquals("测试用户", saved.displayName());
        assertEquals(AccountStatus.ACTIVE, saved.status());
        verify(passwordHasher).hash(PASSWORD);
    }

    @Test
    void 已存在邮箱仍执行密码Hash但不修改用户() {
        enableRegistration(false, false, Set.of());
        when(authPolicy.passwordPolicy()).thenReturn(new PasswordPolicy(12, 128));
        when(passwordHasher.hash(PASSWORD)).thenReturn("discarded-hash");
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        service.create(new RegisterCommand("user@example.com", PASSWORD, "User", null, null));

        verify(passwordHasher).hash(PASSWORD);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void 注册关闭或邮箱域被拒绝时使用相同错误类型() {
        when(authPolicy.registrationPolicy()).thenReturn(new RegistrationPolicy(
                false,
                false,
                new EmailDomainPolicy(DomainPolicyMode.BLOCKLIST, Set.of())
        ));
        IdentityApplicationException closed = assertThrows(
                IdentityApplicationException.class,
                () -> service.create(new RegisterCommand("user@example.com", PASSWORD, "User", null, null))
        );

        when(authPolicy.registrationPolicy()).thenReturn(new RegistrationPolicy(
                true,
                false,
                new EmailDomainPolicy(DomainPolicyMode.BLOCKLIST, Set.of("example.com"))
        ));
        IdentityApplicationException rejected = assertThrows(
                IdentityApplicationException.class,
                () -> service.create(new RegisterCommand("user@example.com", PASSWORD, "User", null, null))
        );

        assertEquals(IdentityErrorCode.REGISTRATION_NOT_ALLOWED, closed.errorCode());
        assertEquals(closed.errorCode(), rejected.errorCode());
        verify(passwordHasher, never()).hash(any());
    }

    @Test
    void 未接入的邮箱验证或接受审计策略必须失败关闭() {
        enableRegistration(true, true, Set.of());
        IdentityApplicationException verification = assertThrows(
                IdentityApplicationException.class,
                () -> service.create(new RegisterCommand("user@example.com", PASSWORD, "User", null, null))
        );

        enableRegistration(false, true, Set.of());
        IdentityApplicationException acceptance = assertThrows(
                IdentityApplicationException.class,
                () -> service.create(new RegisterCommand("user@example.com", PASSWORD, "User", "v1", "v1"))
        );

        assertEquals(IdentityErrorCode.REGISTRATION_UNAVAILABLE, verification.errorCode());
        assertEquals(verification.errorCode(), acceptance.errorCode());
        verify(passwordHasher, never()).hash(any());
    }

    @Test
    void 拒绝弱密码和空白显示名称且命令字符串不泄露输入() {
        enableRegistration(false, false, Set.of());
        when(authPolicy.passwordPolicy()).thenReturn(new PasswordPolicy(16, 128));
        RegisterCommand weakPassword = new RegisterCommand(
                "user@example.com", "short", "User", "terms-v1", "privacy-v1"
        );
        IdentityApplicationException exception = assertThrows(
                IdentityApplicationException.class,
                () -> service.create(weakPassword)
        );

        assertEquals(IdentityErrorCode.INVALID_REQUEST, exception.errorCode());
        assertFalse(weakPassword.toString().contains("short"));
        assertFalse(weakPassword.toString().contains("user@example.com"));
        assertTrue(weakPassword.toString().contains("<REDACTED>"));
    }

    private void enableRegistration(boolean verificationRequired, boolean acceptanceRequired, Set<String> blocked) {
        when(authPolicy.registrationPolicy()).thenReturn(new RegistrationPolicy(
                true,
                verificationRequired,
                new EmailDomainPolicy(DomainPolicyMode.BLOCKLIST, blocked)
        ));
        when(authPolicy.requiresAcceptanceAudit()).thenReturn(acceptanceRequired);
    }
}
