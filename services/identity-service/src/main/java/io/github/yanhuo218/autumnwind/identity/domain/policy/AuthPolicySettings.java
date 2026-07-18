package io.github.yanhuo218.autumnwind.identity.domain.policy;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record AuthPolicySettings(
        boolean publicRegistrationEnabled,
        boolean emailVerificationRequired,
        DomainPolicyMode emailDomainPolicyMode,
        Set<String> emailDomains,
        int verificationValidityMinutes,
        int verificationResendCooldownSeconds,
        int verificationFailureLimit,
        int passwordMinimumLength,
        int loginFailureLimit,
        int loginLockMinutes,
        boolean termsAcceptanceRequired,
        boolean privacyAcceptanceRequired
) {

    public AuthPolicySettings {
        Objects.requireNonNull(emailDomainPolicyMode, "邮箱域策略模式不能为空。");
        Objects.requireNonNull(emailDomains, "邮箱域集合不能为空。");
        if (verificationValidityMinutes < 5 || verificationValidityMinutes > 10080) {
            throw new IllegalArgumentException("邮箱验证有效期不合法。");
        }
        if (verificationResendCooldownSeconds < 1 || verificationResendCooldownSeconds > 86400) {
            throw new IllegalArgumentException("邮箱验证重发冷却时间不合法。");
        }
        if (verificationFailureLimit < 1 || verificationFailureLimit > 100) {
            throw new IllegalArgumentException("邮箱验证失败次数不合法。");
        }
        if (passwordMinimumLength < 12 || passwordMinimumLength > 128) {
            throw new IllegalArgumentException("密码最小长度不合法。");
        }
        if (loginFailureLimit < 1 || loginFailureLimit > 100) {
            throw new IllegalArgumentException("登录失败次数不合法。");
        }
        if (loginLockMinutes < 1 || loginLockMinutes > 1440) {
            throw new IllegalArgumentException("登录锁定时长不合法。");
        }
        EmailDomainPolicy normalized = new EmailDomainPolicy(emailDomainPolicyMode, emailDomains);
        emailDomains = new LinkedHashSet<>(normalized.domains());
    }

    public int verificationTtlSeconds() {
        return Math.multiplyExact(verificationValidityMinutes, 60);
    }

    public int loginLockDurationSeconds() {
        return Math.multiplyExact(loginLockMinutes, 60);
    }
}
