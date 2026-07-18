package io.github.yanhuo218.autumnwind.identity.application.administration;

import io.github.yanhuo218.autumnwind.identity.domain.policy.AuthPolicySettings;

import java.util.Objects;
import java.util.UUID;

public record AuthPolicyUpdateCommand(
        long expectedVersion,
        AuthPolicySettings settings,
        UUID actorUserId
) {

    public AuthPolicyUpdateCommand {
        if (expectedVersion < 1) {
            throw new IllegalArgumentException("策略版本必须为正数。");
        }
        Objects.requireNonNull(settings, "认证策略不能为空。");
        Objects.requireNonNull(actorUserId, "操作者不能为空。");
    }
}
