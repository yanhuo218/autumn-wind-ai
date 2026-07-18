package io.github.yanhuo218.autumnwind.identity.application.administration;

import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.domain.account.AccountStatus;
import io.github.yanhuo218.autumnwind.identity.domain.account.UserRole;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthSessionRepository;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAdministrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T09:00:00Z");
    private static final UUID USER_ID = UUID.fromString("4c184ec5-9127-4f43-a4b9-662d5e38846b");
    private static final UUID ACTOR_ID = UUID.fromString("a7b2c3d4-e5f6-4789-9012-3456789abcde");

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuthSessionRepository sessionRepository = mock(AuthSessionRepository.class);
    private final UserAdministrationService service = new UserAdministrationService(
            userRepository,
            sessionRepository,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );
    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = mock(UserEntity.class);
        when(user.id()).thenReturn(USER_ID);
        when(user.email()).thenReturn("user@example.com");
        when(user.displayName()).thenReturn("User");
        when(user.role()).thenReturn(UserRole.USER);
        when(user.status()).thenReturn(AccountStatus.ACTIVE);
        when(user.isEmailVerified()).thenReturn(true);
        when(user.createdAt()).thenReturn(NOW.minusSeconds(3600));
        when(user.updatedAt()).thenReturn(NOW);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void 列表查询返回分页用户视图() {
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));

        UserPage page = service.listUsers(new UserListQuery("user", AccountStatus.ACTIVE, 0, 20));

        assertEquals(1, page.totalElements());
        assertEquals(USER_ID, page.items().getFirst().id());
        assertEquals(AccountStatus.ACTIVE, page.items().getFirst().status());
    }

    @Test
    void 禁用用户会撤销全部会话并保存状态() {
        service.disableUser(USER_ID, ACTOR_ID);

        verify(user).disable(NOW);
        verify(sessionRepository).revokeAllByUserId(USER_ID, NOW);
        verify(userRepository).saveAndFlush(user);
    }

    @Test
    void 启用用户只允许从禁用状态恢复() {
        when(user.status()).thenReturn(AccountStatus.DISABLED);

        UserAdminView view = service.enableUser(USER_ID, ACTOR_ID);

        verify(user).enable(NOW);
        verify(userRepository).saveAndFlush(user);
        assertEquals(USER_ID, view.id());
    }

    @Test
    void 找不到用户返回稳定错误且不撤销会话() {
        UUID missingId = UUID.randomUUID();
        when(userRepository.findById(missingId)).thenReturn(Optional.empty());

        IdentityApplicationException exception = assertThrows(
                IdentityApplicationException.class,
                () -> service.disableUser(missingId, ACTOR_ID)
        );

        assertEquals(IdentityErrorCode.USER_NOT_FOUND, exception.errorCode());
        verify(sessionRepository, never()).revokeAllByUserId(any(), any());
    }

    @Test
    void 删除态用户不能被重新启用() {
        when(user.status()).thenReturn(AccountStatus.DELETED);

        IdentityApplicationException exception = assertThrows(
                IdentityApplicationException.class,
                () -> service.enableUser(USER_ID, ACTOR_ID)
        );

        assertEquals(IdentityErrorCode.ACCOUNT_STATE_CONFLICT, exception.errorCode());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void 读取用户详情返回用户视图() {
        UserAdminView view = service.getUser(USER_ID);

        assertEquals(USER_ID, view.id());
        assertEquals("user@example.com", view.email());
    }
}
