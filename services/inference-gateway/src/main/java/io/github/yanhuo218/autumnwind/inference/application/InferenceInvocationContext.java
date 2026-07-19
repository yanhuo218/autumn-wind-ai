package io.github.yanhuo218.autumnwind.inference.application;

import java.util.UUID;

public record InferenceInvocationContext(UUID generationId, UUID invocationAttemptId) {

    public static final Class<InferenceInvocationContext> REACTOR_CONTEXT_KEY = InferenceInvocationContext.class;
}
