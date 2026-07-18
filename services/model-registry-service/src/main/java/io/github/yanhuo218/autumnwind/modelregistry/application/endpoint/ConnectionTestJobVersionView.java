package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointConnectionTestJobEntity;

import java.util.UUID;

public record ConnectionTestJobVersionView(UUID jobId, long jobVersion) {

    public static ConnectionTestJobVersionView from(EndpointConnectionTestJobEntity entity) {
        return new ConnectionTestJobVersionView(entity.id(), entity.version());
    }
}
