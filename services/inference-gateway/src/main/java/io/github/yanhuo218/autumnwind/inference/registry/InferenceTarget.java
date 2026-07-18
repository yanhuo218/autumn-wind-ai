package io.github.yanhuo218.autumnwind.inference.registry;

import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record InferenceTarget(
        UUID ownerUserId,
        UUID modelId,
        String providerModelId,
        long modelVersion,
        UUID endpointId,
        URI endpointBaseUrl,
        String endpointProtocol,
        int endpointRequestTimeoutSeconds,
        long endpointVersion,
        Capabilities capabilities,
        UUID credentialId,
        EncryptedSecret credential
) {

    public InferenceTarget {
        Objects.requireNonNull(ownerUserId, "用户标识不能为空。");
        Objects.requireNonNull(modelId, "模型标识不能为空。");
        providerModelId = requireText(providerModelId, "服务商模型标识不能为空。");
        if (modelVersion < 0) {
            throw new IllegalArgumentException("模型版本不能为负数。");
        }
        Objects.requireNonNull(endpointId, "端点标识不能为空。");
        Objects.requireNonNull(endpointBaseUrl, "端点地址不能为空。");
        endpointProtocol = requireText(endpointProtocol, "端点协议不能为空。");
        if (endpointRequestTimeoutSeconds < 1 || endpointRequestTimeoutSeconds > 120) {
            throw new IllegalArgumentException("端点请求超时不合法。");
        }
        if (endpointVersion < 0) {
            throw new IllegalArgumentException("端点版本不能为负数。");
        }
        Objects.requireNonNull(capabilities, "能力快照不能为空。");
        Objects.requireNonNull(credentialId, "凭据标识不能为空。");
        Objects.requireNonNull(credential, "加密凭据不能为空。");
    }

    @Override
    public String toString() {
        return "InferenceTarget[ownerUserId=" + ownerUserId
                + ", modelId=" + modelId
                + ", providerModelId=" + providerModelId
                + ", modelVersion=" + modelVersion
                + ", endpointId=" + endpointId
                + ", endpointBaseUrl=<REDACTED>"
                + ", endpointProtocol=" + endpointProtocol
                + ", endpointRequestTimeoutSeconds=" + endpointRequestTimeoutSeconds
                + ", endpointVersion=" + endpointVersion
                + ", capabilities=" + capabilities
                + ", credentialId=" + credentialId
                + ", credential=<REDACTED>]";
    }

    private static String requireText(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    public record Capabilities(
            String interfaceType,
            Set<String> inputModalities,
            String outputModality,
            boolean streaming,
            boolean systemPrompt,
            boolean reasoning,
            int contextLength,
            int maxOutputLength
    ) {

        public Capabilities {
            interfaceType = requireText(interfaceType, "接口类型不能为空。");
            inputModalities = Set.copyOf(Objects.requireNonNull(inputModalities, "输入模态不能为空。"));
            if (inputModalities.isEmpty()) {
                throw new IllegalArgumentException("输入模态不能为空。");
            }
            outputModality = requireText(outputModality, "输出模态不能为空。");
            if (contextLength < 1 || maxOutputLength < 1 || maxOutputLength > contextLength) {
                throw new IllegalArgumentException("模型长度能力不合法。");
            }
        }
    }
}
