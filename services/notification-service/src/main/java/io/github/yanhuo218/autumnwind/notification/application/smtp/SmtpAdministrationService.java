package io.github.yanhuo218.autumnwind.notification.application.smtp;

import io.github.yanhuo218.autumnwind.notification.application.NotificationApplicationException;
import io.github.yanhuo218.autumnwind.notification.application.NotificationErrorCode;
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
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

@Service
public class SmtpAdministrationService {

    private static final SecretContext SMTP_SECRET_CONTEXT =
            new SecretContext("platform", "smtp-password", "smtp-config");

    private final SmtpConfigRepository configRepository;
    private final SmtpCredentialRepository credentialRepository;
    private final EmailJobRepository emailJobRepository;
    private final SecretStore secretStore;
    private final Clock clock;

    public SmtpAdministrationService(
            SmtpConfigRepository configRepository,
            SmtpCredentialRepository credentialRepository,
            EmailJobRepository emailJobRepository,
            SecretStore secretStore,
            Clock clock
    ) {
        this.configRepository = configRepository;
        this.credentialRepository = credentialRepository;
        this.emailJobRepository = emailJobRepository;
        this.secretStore = secretStore;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<SmtpConfigView> getConfig() {
        return configRepository.findById(SmtpConfigEntity.SINGLETON_ID).map(SmtpConfigView::from);
    }

    @Transactional
    public SmtpConfigView updateConfig(SmtpConfigUpdateCommand command) {
        Optional<SmtpConfigEntity> existing = configRepository.findById(SmtpConfigEntity.SINGLETON_ID);
        validateExpectedVersion(existing, command.expectedVersion());

        Instant now = clock.instant();
        SmtpCredentialEntity previousCredential = existing.map(SmtpConfigEntity::currentCredential).orElse(null);
        SmtpCredentialEntity selectedCredential = selectCredential(command, previousCredential, now);
        SmtpConfigEntity config = existing.orElseGet(() -> SmtpConfigEntity.create(
                command.settings(),
                selectedCredential,
                command.actorUserId(),
                now
        ));
        if (existing.isPresent()) {
            config.update(command.settings(), selectedCredential, command.actorUserId(), now);
        }
        if (previousCredential != null && previousCredential != selectedCredential) {
            previousCredential.markReplaced(now);
        }

        return SmtpConfigView.from(saveConfig(config));
    }

    @Transactional
    public TestEmailJobView createTestEmail(TestEmailCommand command) {
        SmtpConfigEntity config = configRepository.findById(SmtpConfigEntity.SINGLETON_ID)
                .orElseThrow(() -> new NotificationApplicationException(
                        NotificationErrorCode.SMTP_CONFIG_UNAVAILABLE,
                        "SMTP 配置尚未创建。"
                ));
        if (config.version() != command.expectedConfigVersion()) {
            throw versionConflict();
        }

        Instant now = clock.instant();
        long testedConfigVersion = config.version();
        UUID jobId = UUID.randomUUID();
        EmailJobEntity job = EmailJobEntity.createSmtpTest(
                jobId,
                UUID.randomUUID(),
                command.actorUserId(),
                command.recipientEmail(),
                command.correlationId(),
                testedConfigVersion,
                now
        );
        EmailJobEntity savedJob = emailJobRepository.save(job);
        config.markTestQueued(jobId, testedConfigVersion, command.actorUserId(), now);
        saveConfig(config);
        return TestEmailJobView.from(savedJob);
    }

    private SmtpCredentialEntity selectCredential(
            SmtpConfigUpdateCommand command,
            SmtpCredentialEntity previousCredential,
            Instant now
    ) {
        if (!command.hasPassword()) {
            return command.clearPassword() ? null : previousCredential;
        }

        byte[] plaintext = command.password().getBytes(StandardCharsets.UTF_8);
        try {
            EncryptedSecret encrypted = secretStore.encrypt(plaintext, SMTP_SECRET_CONTEXT);
            return credentialRepository.save(SmtpCredentialEntity.create(UUID.randomUUID(), encrypted, now));
        } catch (SecretStoreException exception) {
            throw new NotificationApplicationException(
                    NotificationErrorCode.SMTP_SECRET_FAILURE,
                    "SMTP 凭据加密失败。",
                    exception
            );
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private void validateExpectedVersion(Optional<SmtpConfigEntity> existing, long expectedVersion) {
        if (existing.isEmpty() && expectedVersion != 0) {
            throw versionConflict();
        }
        if (existing.isPresent() && existing.get().version() != expectedVersion) {
            throw versionConflict();
        }
    }

    private SmtpConfigEntity saveConfig(SmtpConfigEntity config) {
        try {
            return configRepository.saveAndFlush(config);
        } catch (OptimisticLockingFailureException exception) {
            throw new NotificationApplicationException(
                    NotificationErrorCode.SMTP_CONFIG_VERSION_CONFLICT,
                    "SMTP 配置已被其他请求修改。",
                    exception
            );
        } catch (DataIntegrityViolationException exception) {
            if (isSingletonCreationConflict(exception)) {
                throw new NotificationApplicationException(
                        NotificationErrorCode.SMTP_CONFIG_VERSION_CONFLICT,
                        "SMTP 配置已被其他请求创建。",
                        exception
                );
            }
            throw exception;
        }
    }

    private boolean isSingletonCreationConflict(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                return "smtp_config_pkey".equals(constraintViolation.getConstraintName());
            }
            cause = cause.getCause();
        }
        return false;
    }

    private NotificationApplicationException versionConflict() {
        return new NotificationApplicationException(
                NotificationErrorCode.SMTP_CONFIG_VERSION_CONFLICT,
                "SMTP 配置版本不匹配。"
        );
    }
}
