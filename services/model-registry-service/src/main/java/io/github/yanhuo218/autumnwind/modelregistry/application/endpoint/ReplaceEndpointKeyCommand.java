package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import java.util.Objects;
import java.util.UUID;

public record ReplaceEndpointKeyCommand(
        UUID ownerUserId,
        UUID endpointId,
        String apiKey,
        long expectedVersion
) {

    public ReplaceEndpointKeyCommand {
        Objects.requireNonNull(ownerUserId, "端点所有者不能为空。");
        Objects.requireNonNull(endpointId, "端点标识不能为空。");
        CreateEndpointCommand.validateApiKey(apiKey);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("端点版本不能为负数。");
        }
    }

    @Override
    public String toString() {
        return "ReplaceEndpointKeyCommand[ownerUserId=" + ownerUserId
                + ", endpointId=" + endpointId + ", apiKey=<REDACTED>, expectedVersion="
                + expectedVersion + "]";
    }
}
