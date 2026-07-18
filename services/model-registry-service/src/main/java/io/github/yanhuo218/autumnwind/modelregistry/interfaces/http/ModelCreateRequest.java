package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.model.CreateModelCommand;
import java.util.UUID;

public record ModelCreateRequest(
        UUID endpointId,
        String providerModelId,
        String displayName,
        ModelCapabilitiesRequest capabilities,
        Boolean enabled,
        Boolean defaultModel
) {

    public CreateModelCommand toCommand(UUID ownerUserId) {
        if (endpointId == null || capabilities == null || enabled == null || defaultModel == null) {
            throw new IllegalArgumentException("模型请求字段不完整。");
        }
        return new CreateModelCommand(ownerUserId, endpointId, providerModelId, displayName,
                capabilities.toDomain(), enabled, defaultModel);
    }
}
