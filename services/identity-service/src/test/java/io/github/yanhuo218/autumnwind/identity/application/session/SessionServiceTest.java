package io.github.yanhuo218.autumnwind.identity.application.session;

import io.github.yanhuo218.autumnwind.identity.application.error.InvalidSessionException;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthSessionEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthSessionRepository;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserRepository;
import io.github.yanhuo218.autumnwind.identity.infrastructure.security.SecureTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
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
class SessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private static final String RAW_TOKEN = "raw-session-token";

    @Mock
    private AuthSessionRepository authSessionRepository;

    @Mock
    private UserRepository userRepository;

    private SecureTokenService tokenService;
    private SessionService service;

    @BeforeEach
    void setUp() {
        tokenService = new SecureTokenService();
        service = new SessionService(
                authSessionRepository,
                userRepository,
                tokenService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void 有效会话返回完整视图和活动Introspection() {
        UserEntity user = activeUser();
        AuthSessionEntity session = activeSession(user.id());
        stubSession(session, user);

        SessionView view = service.currentSession(RAW_TOKEN);
        SessionIntrospection introspection = service.introspect(RAW_TOKEN);

        assertEquals(user.id(), view.user().id());
        assertEquals(session.expiresAt(), view.expiresAt());
        assertTrue(introspection.active());
        assertEquals(user.id(), introspection.userId());
    }

    @Test
    void 账户非活动时会话查询失败且Introspection不暴露身份() {
        UserEntity pending = UserEntity.register(
                UUID.randomUUID(), "user@example.com", "hash", "User", true, NOW.minusSeconds(10)
        );
        AuthSessionEntity session = activeSession(pending.id());
        stubSession(session, pending);

        assertThrows(InvalidSessionException.class, () -> service.currentSession(RAW_TOKEN));
        SessionIntrospection introspection = service.introspect(RAW_TOKEN);
        assertFalse(introspection.active());
        assertEquals(null, introspection.userId());
    }

    @Test
    void 过期撤销和非法Token均视为无效() {
        UserEntity user = activeUser();
        AuthSessionEntity expired = new AuthSessionEntity(
                UUID.randomUUID(), user.id(), tokenService.hash(RAW_TOKEN), NOW, NOW.minusSeconds(3600)
        );
        when(authSessionRepository.findByTokenHash(tokenService.hash(RAW_TOKEN)))
                .thenReturn(Optional.of(expired));

        assertFalse(service.introspect(RAW_TOKEN).active());
        assertFalse(service.introspect(" ").active());
        assertFalse(service.introspect("x".repeat(1025)).active());
        verify(userRepository, never()).findById(user.id());
    }

    @Test
    void 注销使用原子更新且重复注销失败() {
        String tokenHash = tokenService.hash(RAW_TOKEN);
        when(authSessionRepository.revokeActiveByTokenHash(tokenHash, NOW)).thenReturn(1, 0);

        service.logout(RAW_TOKEN);
        assertThrows(InvalidSessionException.class, () -> service.logout(RAW_TOKEN));

        verify(authSessionRepository, org.mockito.Mockito.times(2))
                .revokeActiveByTokenHash(tokenHash, NOW);
    }

    private void stubSession(AuthSessionEntity session, UserEntity user) {
        when(authSessionRepository.findByTokenHash(tokenService.hash(RAW_TOKEN)))
                .thenReturn(Optional.of(session));
        when(userRepository.findById(user.id())).thenReturn(Optional.of(user));
    }

    private static UserEntity activeUser() {
        return UserEntity.register(
                UUID.randomUUID(), "user@example.com", "hash", "User", false, NOW.minusSeconds(10)
        );
    }

    private AuthSessionEntity activeSession(UUID userId) {
        return new AuthSessionEntity(
                UUID.randomUUID(), userId, tokenService.hash(RAW_TOKEN), NOW.plusSeconds(3600), NOW.minusSeconds(10)
        );
    }
}
