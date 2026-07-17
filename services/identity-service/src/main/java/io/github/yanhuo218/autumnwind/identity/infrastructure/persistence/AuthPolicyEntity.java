package io.github.yanhuo218.autumnwind.identity.infrastructure.persistence;

import io.github.yanhuo218.autumnwind.identity.domain.policy.DomainPolicyMode;
import io.github.yanhuo218.autumnwind.identity.domain.policy.EmailDomainPolicy;
import io.github.yanhuo218.autumnwind.identity.domain.policy.PasswordPolicy;
import io.github.yanhuo218.autumnwind.identity.domain.policy.RegistrationPolicy;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "auth_policies", schema = "identity")
public class AuthPolicyEntity {

    @Id
    private short id;

    @Column(name = "public_registration_enabled", nullable = false)
    private boolean publicRegistrationEnabled;

    @Column(name = "email_verification_required", nullable = false)
    private boolean emailVerificationRequired;

    @Enumerated(EnumType.STRING)
    @Column(name = "email_domain_policy_mode", nullable = false, length = 16)
    private DomainPolicyMode emailDomainPolicyMode;

    @Column(name = "password_min_length", nullable = false)
    private int passwordMinimumLength;

    @Column(name = "password_max_length", nullable = false)
    private int passwordMaximumLength;

    @Column(name = "login_failure_limit", nullable = false)
    private int loginFailureLimit;

    @Column(name = "login_lock_duration_seconds", nullable = false)
    private int loginLockDurationSeconds;

    @Column(name = "verification_ttl_seconds", nullable = false)
    private int verificationTtlSeconds;

    @Column(name = "verification_resend_cooldown_seconds", nullable = false)
    private int verificationResendCooldownSeconds;

    @Column(name = "verification_failure_limit", nullable = false)
    private int verificationFailureLimit;

    @Column(name = "password_reset_ttl_seconds", nullable = false)
    private int passwordResetTtlSeconds;

    @Column(name = "terms_acceptance_required", nullable = false)
    private boolean termsAcceptanceRequired;

    @Column(name = "privacy_acceptance_required", nullable = false)
    private boolean privacyAcceptanceRequired;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "auth_policy_email_domains",
            schema = "identity",
            joinColumns = @JoinColumn(name = "policy_id")
    )
    @Column(name = "domain", nullable = false, length = 253)
    private Set<String> emailDomains = new LinkedHashSet<>();

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AuthPolicyEntity() {
    }

    public RegistrationPolicy registrationPolicy() {
        return new RegistrationPolicy(
                publicRegistrationEnabled,
                emailVerificationRequired,
                new EmailDomainPolicy(emailDomainPolicyMode, emailDomains)
        );
    }

    public PasswordPolicy passwordPolicy() {
        return new PasswordPolicy(passwordMinimumLength, passwordMaximumLength);
    }

    public int loginFailureLimit() {
        return loginFailureLimit;
    }

    public int loginLockDurationSeconds() {
        return loginLockDurationSeconds;
    }

    public boolean requiresAcceptanceAudit() {
        return termsAcceptanceRequired || privacyAcceptanceRequired;
    }

    public boolean termsAcceptanceRequired() {
        return termsAcceptanceRequired;
    }

    public boolean privacyAcceptanceRequired() {
        return privacyAcceptanceRequired;
    }
}
