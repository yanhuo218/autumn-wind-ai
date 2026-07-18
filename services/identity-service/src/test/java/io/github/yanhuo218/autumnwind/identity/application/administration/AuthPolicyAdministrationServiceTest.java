package io.github.yanhuo218.autumnwind.identity.application.administration;

import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.domain.policy.AuthPolicySettings;
import io.github.yanhuo218.autumnwind.identity.domain.policy.DomainPolicyMode;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthPolicyAdministrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T08:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("4c184ec5-9127-4f43-a4b9-662d5e38846b");

    private final AuthPolicyRepository repository = mock(AuthPolicyRepository.class);
    private final AuthPolicyAdministrationService service = new AuthPolicyAdministrationService(
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );
    private AuthPolicyEntity entity;

    @BeforeEach
    void setUp() {
        entity = policyEntity();
    }

    @Test
    void 读取策略时返回外部版本和完整管理视图() {
        when(repository.findById((short) 1)).thenReturn(Optional.of(entity));

        AuthPolicyView view = service.getPolicy();

        assertEquals(1, view.version());
        assertEquals(DomainPolicyMode.BLOCKLIST, view.emailDomainPolicyMode());
        assertEquals(15, view.loginLockMinutes());
        assertEquals(60, view.verificationValidityMinutes());
    }

    @Test
    void 使用匹配版本更新策略并记录操作者() {
        when(repository.findById((short) 1)).thenReturn(Optional.of(entity));
        when(repository.saveAndFlush(entity)).thenAnswer(invocation -> {
            ReflectionTestUtils.setField(entity, "version", 1L);
            return entity;
        });
        AuthPolicySettings settings = new AuthPolicySettings(
                true,
                false,
                DomainPolicyMode.ALLOWLIST,
                Set.of("example.com"),
                120,
                45,
                7,
                14,
                6,
                30,
                true,
                false
        );

        AuthPolicyView view = service.updatePolicy(new AuthPolicyUpdateCommand(1, settings, ACTOR_ID));

        assertEquals(2, view.version());
        assertEquals(ACTOR_ID, view.updatedBy());
        assertEquals(Set.of("example.com"), view.emailDomains());
        verify(repository).saveAndFlush(entity);
    }

    @Test
    void 版本不匹配时不保存并返回稳定冲突错误() {
        when(repository.findById((short) 1)).thenReturn(Optional.of(entity));

        IdentityApplicationException exception = assertThrows(
                IdentityApplicationException.class,
                () -> service.updatePolicy(new AuthPolicyUpdateCommand(2, defaultSettings(), ACTOR_ID))
        );

        assertEquals(IdentityErrorCode.VERSION_CONFLICT, exception.errorCode());
        verify(repository, never()).saveAndFlush(any());
    }

    private static AuthPolicyEntity policyEntity() {
        AuthPolicyEntity policy = new AuthPolicyEntity();
        ReflectionTestUtils.setField(policy, "id", (short) 1);
        ReflectionTestUtils.setField(policy, "publicRegistrationEnabled", false);
        ReflectionTestUtils.setField(policy, "emailVerificationRequired", false);
        ReflectionTestUtils.setField(policy, "emailDomainPolicyMode", DomainPolicyMode.BLOCKLIST);
        ReflectionTestUtils.setField(policy, "emailDomains", new java.util.LinkedHashSet<>());
        ReflectionTestUtils.setField(policy, "passwordMinimumLength", 12);
        ReflectionTestUtils.setField(policy, "passwordMaximumLength", 128);
        ReflectionTestUtils.setField(policy, "loginFailureLimit", 5);
        ReflectionTestUtils.setField(policy, "loginLockDurationSeconds", 900);
        ReflectionTestUtils.setField(policy, "verificationTtlSeconds", 3600);
        ReflectionTestUtils.setField(policy, "verificationResendCooldownSeconds", 30);
        ReflectionTestUtils.setField(policy, "verificationFailureLimit", 5);
        ReflectionTestUtils.setField(policy, "passwordResetTtlSeconds", 3600);
        ReflectionTestUtils.setField(policy, "termsAcceptanceRequired", false);
        ReflectionTestUtils.setField(policy, "privacyAcceptanceRequired", false);
        ReflectionTestUtils.setField(policy, "version", 0L);
        ReflectionTestUtils.setField(policy, "updatedAt", NOW.minusSeconds(3600));
        return policy;
    }

    private static AuthPolicySettings defaultSettings() {
        return new AuthPolicySettings(
                false,
                false,
                DomainPolicyMode.BLOCKLIST,
                Set.of(),
                60,
                30,
                5,
                12,
                5,
                15,
                false,
                false
        );
    }
}
