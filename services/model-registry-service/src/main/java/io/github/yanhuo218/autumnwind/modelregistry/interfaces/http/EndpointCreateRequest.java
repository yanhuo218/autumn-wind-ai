package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.CreateEndpointCommand;
import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointProtocol;
import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointSettings;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

public record EndpointCreateRequest(
        String displayName,
        String baseUrl,
        EndpointProtocol protocol,
        Integer requestTimeoutSeconds,
        Boolean enabled,
        String apiKey
) {

    public CreateEndpointCommand toCommand(UUID ownerUserId) {
        if (requestTimeoutSeconds == null || enabled == null || baseUrl == null) {
            throw new IllegalArgumentException("端点请求字段不完整。");
        }
        return new CreateEndpointCommand(
                ownerUserId,
                new EndpointSettings(
                        displayName,
                        URI.create(baseUrl),
                        protocol,
                        Duration.ofSeconds(requestTimeoutSeconds),
                        enabled
                ),
                apiKey
        );
    }
}
