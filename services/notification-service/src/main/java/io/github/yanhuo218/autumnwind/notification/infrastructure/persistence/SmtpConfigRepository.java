package io.github.yanhuo218.autumnwind.notification.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SmtpConfigRepository extends JpaRepository<SmtpConfigEntity, Short> {
}
