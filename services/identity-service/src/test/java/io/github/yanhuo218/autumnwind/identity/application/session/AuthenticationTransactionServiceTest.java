package io.github.yanhuo218.autumnwind.identity.application.session;

import io.github.yanhuo218.autumnwind.identity.application.error.AuthenticationFailedException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.domain.security.PasswordHasher;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyRepository;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthSessionEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthSessionRepository;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserRepository;
import io.github.yanhuo218.autumnwind.identity.infrastructure.security.SecureTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticationTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @Mock
    private AuthPolicyRepository authPolicyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthSessionRepository authSessionRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private AuthPolicyEntity authPolicy;

    private AuthenticationTransactionService service;

    @BeforeEach
    void setUp() {
        when(passwordHasher.hash("Autumn-Wind-Dummy-Password")).thenReturn("dummy-hash");
        service = new AuthenticationTransactionService(
                authPolicyRepository,
                userRepository,
                authSessionRepository,
                passwordHasher,
                new SecureTokenService(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(7)
        );
    }

    @Test
    void 有效账户登录后只保存会话TokenHash并返回脱敏结果() {
        enablePolicy();
        UserEntity user = activeUser();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("correct-password", "stored-hash")).thenReturn(true);

        LoginResult result = service.login(new LoginCommand("USER@EXAMPLE.COM", "correct-password"));

        ArgumentCaptor<AuthSessionEntity> captor = ArgumentCaptor.forClass(AuthSessionEntity.class);
        verify(authSessionRepository).save(captor.capture());
        assertEquals(NOW, result.session().createdAt());
        assertEquals(NOW.plus(Duration.ofDays(7)), result.session().expiresAt());
        assertEquals(NOW, user.lastLoginAt());
        assertFalse(result.toString().contains(result.rawSessionToken()));
        assertTrue(result.toString().contains("<REDACTED>"));
    }

    @Test
    void 未知邮箱执行DummyHash且返回通用认证失败() {
        enablePolicy();
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());
        when(passwordHasher.matches("wrong-password", "dummy-hash")).thenReturn(false);

        AuthenticationFailedException exception = assertThrows(
                AuthenticationFailedException.class,
                () -> service.login(new LoginCommand("missing@example.com", "wrong-password"))
        );

        assertEquals(IdentityErrorCode.AUTHENTICATION_FAILED, exception.errorCode());
        verify(passwordHasher).matches("wrong-password", "dummy-hash");
        verify(authSessionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 密码错误达到阈值后锁定账户() {
        enablePolicy();
        UserEntity user = activeUser();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("wrong-password", "stored-hash")).thenReturn(false);
        when(authPolicy.loginFailureLimit()).thenReturn(1);
        when(authPolicy.loginLockDurationSeconds()).thenReturn(900);

        assertThrows(
                AuthenticationFailedException.class,
                () -> service.login(new LoginCommand("user@example.com", "wrong-password"))
        );

        assertTrue(user.isLoginLocked(NOW.plusSeconds(1)));
        verify(authSessionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 非活动账户即使密码正确也返回通用认证失败() {
        enablePolicy();
        UserEntity pending = UserEntity.register(
                UUID.randomUUID(), "user@example.com", "stored-hash", "User", true, NOW.minusSeconds(10)
        );
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(pending));
        when(passwordHasher.matches("correct-password", "stored-hash")).thenReturn(true);

        AuthenticationFailedException exception = assertThrows(
                AuthenticationFailedException.class,
                () -> service.login(new LoginCommand("user@example.com", "correct-password"))
        );

        assertEquals(IdentityErrorCode.AUTHENTICATION_FAILED, exception.errorCode());
        verify(authSessionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 无效登录结构在查询账户前被拒绝且命令字符串脱敏() {
        LoginCommand command = new LoginCommand("user@example.com", "");
        IdentityApplicationException exception = assertThrows(
                IdentityApplicationException.class,
                () -> service.login(command)
        );

        assertEquals(IdentityErrorCode.INVALID_REQUEST, exception.errorCode());
        assertFalse(command.toString().contains("user@example.com"));
        verify(authPolicyRepository, never()).findById((short) 1);
    }

    private static UserEntity activeUser() {
        return UserEntity.register(
                UUID.randomUUID(), "user@example.com", "stored-hash", "User", false, NOW.minusSeconds(10)
        );
    }

    private void enablePolicy() {
        when(authPolicyRepository.findById((short) 1)).thenReturn(Optional.of(authPolicy));
    }
}
