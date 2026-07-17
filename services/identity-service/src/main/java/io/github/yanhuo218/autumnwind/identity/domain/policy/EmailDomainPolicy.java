package io.github.yanhuo218.autumnwind.identity.domain.policy;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class EmailDomainPolicy {

    private final DomainPolicyMode mode;
    private final Set<String> domains;

    public EmailDomainPolicy(DomainPolicyMode mode, Collection<String> domains) {
        this.mode = Objects.requireNonNull(mode, "邮箱域策略模式不能为空。");
        Objects.requireNonNull(domains, "邮箱域集合不能为空。");
        if (domains.size() > 500) {
            throw new IllegalArgumentException("邮箱域集合不能超过 500 项。");
        }
        this.domains = domains.stream()
                .map(EmailAddressNormalizer::normalizeDomain)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean allows(NormalizedEmail email) {
        Objects.requireNonNull(email, "邮箱不能为空。");
        boolean listed = domains.contains(email.domain());
        return mode == DomainPolicyMode.ALLOWLIST ? listed : !listed;
    }

    public DomainPolicyMode mode() {
        return mode;
    }

    public Set<String> domains() {
        return domains;
    }
}
