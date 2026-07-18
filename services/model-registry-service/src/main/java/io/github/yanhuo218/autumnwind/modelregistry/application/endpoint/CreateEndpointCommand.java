package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointSettings;

import java.util.Objects;
import java.util.UUID;

public record CreateEndpointCommand(
        UUID ownerUserId,
        EndpointSettings settings,
        String apiKey
) {

    public CreateEndpointCommand {
        Objects.requireNonNull(ownerUserId, "端点所有者不能为空。");
        Objects.requireNonNull(settings, "端点设置不能为空。");
        validateApiKey(apiKey);
    }

    static void validateApiKey(String value) {
        if (value == null || value.isBlank() || value.length() > 4096
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("API Key 长度必须在 1 到 4096 个字符之间且不能包含控制字符。");
        }
    }

    @Override
    public String toString() {
        return "CreateEndpointCommand[ownerUserId=" + ownerUserId
                + ", settings=" + settings + ", apiKey=<REDACTED>]";
    }
}
