package io.github.yanhuo218.autumnwind.modelregistry.application.model;

import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelCapabilities;

import java.util.Objects;
import java.util.UUID;

public record UpdateModelCommand(
        UUID ownerUserId,
        UUID modelId,
        String providerModelId,
        String displayName,
        ModelCapabilities capabilities,
        boolean enabled,
        boolean defaultModel,
        long expectedVersion
) {

    public UpdateModelCommand {
        Objects.requireNonNull(ownerUserId, "模型所有者不能为空。");
        Objects.requireNonNull(modelId, "模型标识不能为空。");
        providerModelId = ModelCommandFields.normalizeProviderModelId(providerModelId);
        displayName = ModelCommandFields.normalizeDisplayName(displayName);
        Objects.requireNonNull(capabilities, "模型能力不能为空。");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("模型版本不能为负数。");
        }
    }
}
