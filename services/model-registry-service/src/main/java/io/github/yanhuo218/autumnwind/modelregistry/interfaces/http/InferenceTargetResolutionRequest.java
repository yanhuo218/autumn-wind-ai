package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import java.util.Objects;
import java.util.UUID;

public record InferenceTargetResolutionRequest(UUID ownerUserId, UUID modelId) {

    public InferenceTargetResolutionRequest {
        Objects.requireNonNull(ownerUserId, "用户标识不能为空。");
        Objects.requireNonNull(modelId, "模型标识不能为空。");
    }
}
