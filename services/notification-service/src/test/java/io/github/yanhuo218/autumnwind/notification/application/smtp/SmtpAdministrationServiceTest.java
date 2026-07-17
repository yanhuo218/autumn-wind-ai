package io.github.yanhuo218.autumnwind.notification.application.smtp;

import io.github.yanhuo218.autumnwind.notification.application.NotificationApplicationException;
import io.github.yanhuo218.autumnwind.notification.application.NotificationErrorCode;
import io.github.yanhuo218.autumnwind.notification.domain.email.EmailPurpose;
import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpSecurityMode;
import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpSettings;
import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpTestStatus;
import io.github.yanhuo218.autumnwind.notification.infrastructure.persistence.EmailJobEntity;
import io.github.yanhuo218.autumnwind.notification.infrastructure.persistence.EmailJobRepository;
import io.github.yanhuo218.autumnwind.notification.infrastructure.persistence.SmtpConfigEntity;
import io.github.yanhuo218.autumnwind.notification.infrastructure.persistence.SmtpConfigRepository;
import io.github.yanhuo218.autumnwind.notification.infrastructure.persistence.SmtpCredentialEntity;
import io.github.yanhuo218.autumnwind.notification.infrastructure.persistence.SmtpCredentialRepository;
import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import io.github.yanhuo218.autumnwind.security.secrets.SecretContext;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStore;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmtpAdministrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final SecretContext SMTP_CONTEXT =
            new SecretContext("platform", "smtp-password", "smtp-config");

    @Mock
    private SmtpConfigRepository configRepository;

    @Mock
    private SmtpCredentialRepository credentialRepository;

    @Mock
    private EmailJobRepository emailJobRepository;

    @Mock
    private SecretStore secretStore;

    private SmtpAdministrationService service;

    @BeforeEach
    void setUp() {
        service = new SmtpAdministrationService(
                configRepository,
                credentialRepository,
                emailJobRepository,
                secretStore,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void 创建无密码配置且读取视图不含凭据内容() {
        when(configRepository.findById(SmtpConfigEntity.SINGLETON_ID)).thenReturn(Optional.empty());
        when(configRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SmtpConfigView view = service.updateConfig(updateCommand(null, false, 0));

        assertFalse(view.passwordConfigured());
        assertEquals("smtp.example.com", view.host());
        verifyNoInteractions(secretStore, credentialRepository, emailJobRepository);
    }

    @Test
    void 使用固定上下文加密密码并清空临时明文字节() {
        when(configRepository.findById(SmtpConfigEntity.SINGLETON_ID)).thenReturn(Optional.empty());
        when(configRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(credentialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AtomicReference<byte[]> plaintextCopy = new AtomicReference<>();
        AtomicReference<byte[]> plaintextReference = new AtomicReference<>();
        when(secretStore.encrypt(any(), eq(SMTP_CONTEXT))).thenAnswer(invocation -> {
            byte[] plaintext = invocation.getArgument(0);
            plaintextReference.set(plaintext);
            plaintextCopy.set(Arrays.copyOf(plaintext, plaintext.length));
            return encryptedSecret();
        });

        SmtpConfigView view = service.updateConfig(updateCommand("not-a-real-password", false, 0));

        assertTrue(view.passwordConfigured());
        assertEquals("not-a-real-password", new String(plaintextCopy.get(), java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(Arrays.equals(new byte[plaintextReference.get().length], plaintextReference.get()));
    }

    @Test
    void 省略密码时保留旧凭据且不调用SecretStore() {
        SmtpCredentialEntity credential = credential();
        SmtpConfigEntity config = config(credential);
        when(configRepository.findById(SmtpConfigEntity.SINGLETON_ID)).thenReturn(Optional.of(config));
        when(configRepository.saveAndFlush(config)).thenReturn(config);

        SmtpConfigView view = service.updateConfig(updateCommand(null, false, 0));

        assertTrue(view.passwordConfigured());
        assertNull(credential.replacedAt());
        verifyNoInteractions(secretStore, credentialRepository, emailJobRepository);
    }

    @Test
    void 替换密码时切换引用并标记旧凭据() {
        SmtpCredentialEntity previous = credential();
        SmtpConfigEntity config = config(previous);
        when(configRepository.findById(SmtpConfigEntity.SINGLETON_ID)).thenReturn(Optional.of(config));
        when(secretStore.encrypt(any(), eq(SMTP_CONTEXT))).thenReturn(encryptedSecret());
        when(credentialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configRepository.saveAndFlush(config)).thenReturn(config);

        SmtpConfigView view = service.updateConfig(updateCommand("replacement", false, 0));

        assertTrue(view.passwordConfigured());
        assertEquals(NOW, previous.replacedAt());
    }

    @Test
    void 清除密码时解除引用并标记旧凭据() {
        SmtpCredentialEntity previous = credential();
        SmtpConfigEntity config = config(previous);
        when(configRepository.findById(SmtpConfigEntity.SINGLETON_ID)).thenReturn(Optional.of(config));
        when(configRepository.saveAndFlush(config)).thenReturn(config);

        SmtpConfigView view = service.updateConfig(updateCommand(null, true, 0));

        assertFalse(view.passwordConfigured());
        assertEquals(NOW, previous.replacedAt());
        verifyNoInteractions(secretStore, credentialRepository, emailJobRepository);
    }

    @Test
    void 更新配置时重置旧测试状态() {
        SmtpConfigEntity config = config(null);
        config.markTestQueued(UUID.randomUUID(), 0, ACTOR_ID, NOW.minusSeconds(30));
        when(configRepository.findById(SmtpConfigEntity.SINGLETON_ID)).thenReturn(Optional.of(config));
        when(configRepository.saveAndFlush(config)).thenReturn(config);

        SmtpConfigView view = service.updateConfig(updateCommand(null, false, 0));

        assertEquals(SmtpTestStatus.NEVER, view.lastTestStatus());
        assertNull(view.lastTestedAt());
    }

    @Test
    void 版本不匹配时在加密和写库前返回冲突() {
        when(configRepository.findById(SmtpConfigEntity.SINGLETON_ID)).thenReturn(Optional.empty());

        NotificationApplicationException exception = assertThrows(
                NotificationApplicationException.class,
                () -> service.updateConfig(updateCommand("not-a-real-password", false, 1))
        );

        assertEquals(NotificationErrorCode.SMTP_CONFIG_VERSION_CONFLICT, exception.code());
        verifyNoInteractions(secretStore, credentialRepository, emailJobRepository);
        verify(configRepository, never()).saveAndFlush(any());
    }

    @Test
    void SecretStore失败时不修改已有配置() {
        SmtpCredentialEntity previous = credential();
        SmtpConfigEntity config = config(previous);
        when(configRepository.findById(SmtpConfigEntity.SINGLETON_ID)).thenReturn(Optional.of(config));
        when(secretStore.encrypt(any(), eq(SMTP_CONTEXT))).thenThrow(new SecretStoreException("测试失败"));

        NotificationApplicationException exception = assertThrows(
                NotificationApplicationException.class,
                () -> service.updateConfig(updateCommand("not-a-real-password", false, 0))
        );

        assertEquals(NotificationErrorCode.SMTP_SECRET_FAILURE, exception.code());
        assertEquals(previous, config.currentCredential());
        assertNull(previous.replacedAt());
        verify(configRepository, never()).saveAndFlush(any());
    }

    @Test
    void 数据库乐观锁失败映射为版本冲突() {
        SmtpConfigEntity config = config(null);
        when(configRepository.findById(SmtpConfigEntity.SINGLETON_ID)).thenReturn(Optional.of(config));
        when(configRepository.saveAndFlush(config)).thenThrow(new OptimisticLockingFailureException("测试冲突"));

        NotificationApplicationException exception = assertThrows(
                NotificationApplicationException.class,
                () -> service.updateConfig(updateCommand(null, false, 0))
        );

        assertEquals(NotificationErrorCode.SMTP_CONFIG_VERSION_CONFLICT, exception.code());
    }

    @Test
    void 读取配置不解密凭据() {
        when(configRepository.findById(SmtpConfigEntity.SINGLETON_ID)).thenReturn(Optional.of(config(credential())));

        Optional<SmtpConfigView> view = service.getConfig();

        assertTrue(view.orElseThrow().passwordConfigured());
        verifyNoInteractions(secretStore, credentialRepository, emailJobRepository);
    }

    @Test
    void 测试邮件只创建排队任务并更新配置状态() {
        SmtpConfigEntity config = config(null);
        when(configRepository.findById(SmtpConfigEntity.SINGLETON_ID)).thenReturn(Optional.of(config));
        when(emailJobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configRepository.saveAndFlush(config)).thenReturn(config);

        TestEmailJobView view = service.createTestEmail(new TestEmailCommand(
                " Recipient@Example.COM ",
                0,
                ACTOR_ID,
                "correlation-1"
        ));

        assertEquals(io.github.yanhuo218.autumnwind.notification.domain.email.EmailJobStatus.QUEUED, view.status());
        assertEquals(SmtpTestStatus.QUEUED, config.lastTestStatus());
        ArgumentCaptor<EmailJobEntity> jobCaptor = ArgumentCaptor.forClass(EmailJobEntity.class);
        verify(emailJobRepository).save(jobCaptor.capture());
        assertEquals(EmailPurpose.SMTP_TEST, jobCaptor.getValue().purpose());
        assertEquals("Recipient@example.com", jobCaptor.getValue().recipientEmail());
        assertEquals(0, jobCaptor.getValue().smtpConfigVersion());
        assertEquals(view.jobId(), config.lastTestJobId());
        assertEquals(0, config.lastTestConfigVersion());
        verifyNoInteractions(secretStore, credentialRepository);
    }

    @Test
    void 过期配置版本不会创建测试任务() {
        SmtpConfigEntity config = config(null);
        when(configRepository.findById(SmtpConfigEntity.SINGLETON_ID)).thenReturn(Optional.of(config));

        NotificationApplicationException exception = assertThrows(
                NotificationApplicationException.class,
                () -> service.createTestEmail(new TestEmailCommand(
                        "recipient@example.com",
                        1,
                        ACTOR_ID,
                        "correlation-1"
                ))
        );

        assertEquals(NotificationErrorCode.SMTP_CONFIG_VERSION_CONFLICT, exception.code());
        assertEquals(SmtpTestStatus.NEVER, config.lastTestStatus());
        verifyNoInteractions(secretStore, credentialRepository, emailJobRepository);
        verify(configRepository, never()).saveAndFlush(any());
    }

    @Test
    void 未创建配置时拒绝测试邮件任务() {
        when(configRepository.findById(SmtpConfigEntity.SINGLETON_ID)).thenReturn(Optional.empty());

        NotificationApplicationException exception = assertThrows(
                NotificationApplicationException.class,
                () -> service.createTestEmail(new TestEmailCommand(
                        "recipient@example.com",
                        0,
                        ACTOR_ID,
                        "correlation-1"
                ))
        );

        assertEquals(NotificationErrorCode.SMTP_CONFIG_UNAVAILABLE, exception.code());
        verifyNoInteractions(secretStore, credentialRepository, emailJobRepository);
    }

    private static SmtpConfigUpdateCommand updateCommand(String password, boolean clearPassword, long version) {
        return new SmtpConfigUpdateCommand(settings(), password, clearPassword, version, ACTOR_ID);
    }

    private static SmtpSettings settings() {
        return new SmtpSettings(
                "smtp.example.com",
                587,
                SmtpSecurityMode.STARTTLS,
                "user",
                "sender@example.com",
                "Autumn Wind Ai"
        );
    }

    private static SmtpConfigEntity config(SmtpCredentialEntity credential) {
        return SmtpConfigEntity.create(settings(), credential, ACTOR_ID, NOW.minusSeconds(60));
    }

    private static SmtpCredentialEntity credential() {
        return SmtpCredentialEntity.create(UUID.randomUUID(), encryptedSecret(), NOW.minusSeconds(120));
    }

    private static EncryptedSecret encryptedSecret() {
        return new EncryptedSecret(1, "local-v1", new byte[12], new byte[48], new byte[12], new byte[16]);
    }
}
