package io.github.yanhuo218.autumnwind.modelregistry.application.inference;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ModelRegistryApplicationException;
import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ModelRegistryErrorCode;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelInterfaceType;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointCredentialEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointRepository;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.ModelEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.ModelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class InferenceTargetResolutionService {

    private final ModelRepository modelRepository;
    private final EndpointRepository endpointRepository;

    public InferenceTargetResolutionService(ModelRepository modelRepository, EndpointRepository endpointRepository) {
        this.modelRepository = Objects.requireNonNull(modelRepository, "模型仓库不能为空。");
        this.endpointRepository = Objects.requireNonNull(endpointRepository, "端点仓库不能为空。");
    }

    @Transactional(readOnly = true)
    public InferenceTargetView resolve(UUID ownerUserId, UUID modelId) {
        Objects.requireNonNull(ownerUserId, "模型所有者不能为空。");
        Objects.requireNonNull(modelId, "模型标识不能为空。");
        ModelEntity model = modelRepository.findByIdAndOwnerUserId(modelId, ownerUserId)
                .orElseThrow(() -> new ModelRegistryApplicationException(
                        ModelRegistryErrorCode.MODEL_NOT_FOUND,
                        "模型不存在。"
                ));
        if (!model.enabled() || model.interfaceType() != ModelInterfaceType.CHAT_COMPLETIONS) {
            throw unavailable();
        }

        EndpointEntity endpoint = endpointRepository.findByIdAndOwnerUserId(model.endpointId(), ownerUserId)
                .orElseThrow(InferenceTargetResolutionService::unavailable);
        EndpointCredentialEntity credential = endpoint.currentCredential();
        if (!endpoint.enabled() || credential == null) {
            throw unavailable();
        }

        return new InferenceTargetView(
                model.id(),
                model.providerModelId(),
                model.version(),
                endpoint.id(),
                endpoint.baseUrl(),
                endpoint.protocol(),
                endpoint.requestTimeoutSeconds(),
                endpoint.version(),
                model.capabilities(),
                credential.id(),
                EncryptedCredentialEnvelope.from(credential.toEncryptedSecret())
        );
    }

    private static ModelRegistryApplicationException unavailable() {
        return new ModelRegistryApplicationException(
                ModelRegistryErrorCode.INFERENCE_TARGET_UNAVAILABLE,
                "模型推理目标当前不可用。"
        );
    }
}
