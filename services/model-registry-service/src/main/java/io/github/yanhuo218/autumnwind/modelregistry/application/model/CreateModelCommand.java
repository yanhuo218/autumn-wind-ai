package io.github.yanhuo218.autumnwind.modelregistry.application.model;

import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelCapabilities;

import java.util.Objects;
import java.util.UUID;

public record CreateModelCommand(
        UUID ownerUserId,
        UUID endpointId,
        String providerModelId,
        String displayName,
        ModelCapabilities capabilities,
        boolean enabled,
        boolean defaultModel
) {

    public CreateModelCommand {
        Objects.requireNonNull(ownerUserId, "模型所有者不能为空。");
        Objects.requireNonNull(endpointId, "端点标识不能为空。");
        providerModelId = ModelCommandFields.normalizeProviderModelId(providerModelId);
        displayName = ModelCommandFields.normalizeDisplayName(displayName);
        Objects.requireNonNull(capabilities, "模型能力不能为空。");
    }
}
