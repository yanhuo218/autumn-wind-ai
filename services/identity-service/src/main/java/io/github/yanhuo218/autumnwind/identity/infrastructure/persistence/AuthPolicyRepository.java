package io.github.yanhuo218.autumnwind.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthPolicyRepository extends JpaRepository<AuthPolicyEntity, Short> {
}
