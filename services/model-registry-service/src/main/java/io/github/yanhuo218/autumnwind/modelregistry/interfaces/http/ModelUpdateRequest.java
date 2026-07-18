package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.model.UpdateModelCommand;

import java.util.UUID;

public record ModelUpdateRequest(
        String providerModelId,
        String displayName,
        ModelCapabilitiesRequest capabilities,
        Boolean enabled,
        Boolean defaultModel,
        Long expectedVersion
) {

    public UpdateModelCommand toCommand(UUID ownerUserId, UUID modelId) {
        if (capabilities == null || enabled == null || defaultModel == null || expectedVersion == null) {
            throw new IllegalArgumentException("模型更新请求字段不完整。");
        }
        return new UpdateModelCommand(ownerUserId, modelId, providerModelId, displayName,
                capabilities.toDomain(), enabled, defaultModel, expectedVersion);
    }
}
