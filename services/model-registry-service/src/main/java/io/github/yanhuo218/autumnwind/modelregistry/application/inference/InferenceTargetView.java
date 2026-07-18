package io.github.yanhuo218.autumnwind.modelregistry.application.inference;

import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointProtocol;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelCapabilities;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

public record InferenceTargetView(
        UUID modelId,
        String providerModelId,
        long modelVersion,
        UUID endpointId,
        URI endpointBaseUrl,
        EndpointProtocol endpointProtocol,
        int endpointRequestTimeoutSeconds,
        long endpointVersion,
        ModelCapabilities capabilities,
        UUID credentialId,
        EncryptedCredentialEnvelope credential
) {

    public InferenceTargetView {
        Objects.requireNonNull(modelId, "模型标识不能为空。");
        Objects.requireNonNull(providerModelId, "服务商模型标识不能为空。");
        Objects.requireNonNull(endpointId, "端点标识不能为空。");
        Objects.requireNonNull(endpointBaseUrl, "端点地址不能为空。");
        Objects.requireNonNull(endpointProtocol, "端点协议不能为空。");
        Objects.requireNonNull(capabilities, "模型能力不能为空。");
        Objects.requireNonNull(credentialId, "凭据标识不能为空。");
        Objects.requireNonNull(credential, "加密凭据不能为空。");
    }
}
