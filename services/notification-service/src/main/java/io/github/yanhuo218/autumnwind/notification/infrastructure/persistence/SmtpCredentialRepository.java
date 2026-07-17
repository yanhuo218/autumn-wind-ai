package io.github.yanhuo218.autumnwind.notification.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SmtpCredentialRepository extends JpaRepository<SmtpCredentialEntity, UUID> {
}
