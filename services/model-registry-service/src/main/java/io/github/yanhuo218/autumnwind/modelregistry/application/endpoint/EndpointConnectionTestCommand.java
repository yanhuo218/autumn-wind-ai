package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record EndpointConnectionTestCommand(
        UUID ownerUserId,
        UUID endpointId,
        UUID requestedByUserId,
        String correlationId,
        long expectedVersion
) {

    private static final Pattern CORRELATION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{16,64}$");

    public EndpointConnectionTestCommand {
        Objects.requireNonNull(ownerUserId, "端点所有者不能为空。");
        Objects.requireNonNull(endpointId, "端点标识不能为空。");
        Objects.requireNonNull(requestedByUserId, "连接测试操作者不能为空。");
        if (correlationId == null || !CORRELATION_ID_PATTERN.matcher(correlationId).matches()) {
            throw new IllegalArgumentException("连接测试关联标识不合法。");
        }
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("端点版本不能为负数。");
        }
    }
}
