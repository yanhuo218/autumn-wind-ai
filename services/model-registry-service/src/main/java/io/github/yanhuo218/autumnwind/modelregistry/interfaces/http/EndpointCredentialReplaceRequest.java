package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ReplaceEndpointKeyCommand;

import java.util.UUID;

public record EndpointCredentialReplaceRequest(String apiKey, Long expectedVersion) {

    public ReplaceEndpointKeyCommand toCommand(UUID ownerUserId, UUID endpointId) {
        if (expectedVersion == null) {
            throw new IllegalArgumentException("端点版本不能为空。");
        }
        return new ReplaceEndpointKeyCommand(ownerUserId, endpointId, apiKey, expectedVersion);
    }
}
