package io.github.yanhuo218.autumnwind.inference.registry;

import reactor.core.publisher.Mono;

import java.util.UUID;

public interface InferenceTargetClient {

    Mono<InferenceTarget> resolve(UUID ownerUserId, UUID modelId, String correlationId);
}
