package io.github.yanhuo218.autumnwind.inference.chat;

import io.github.yanhuo218.autumnwind.inference.credentials.EndpointCredentialResolver;
import io.github.yanhuo218.autumnwind.inference.registry.InferenceTarget;
import io.github.yanhuo218.autumnwind.inference.security.OutboundTargetPolicy;
import io.github.yanhuo218.autumnwind.inference.security.TargetPolicyException;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderExchangeClient;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderExchangeLimits;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderFrame;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderRequest;
import io.github.yanhuo218.autumnwind.inference.chat.InferenceEvent.ErrorCode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OpenAiChatCompletionsAdapter {

    private static final int MAX_RETRIES = 2;
    private static final Set<String> MESSAGE_ROLES = Set.of("user", "assistant");

    private final ObjectMapper objectMapper;
    private final OutboundTargetPolicy targetPolicy;
    private final EndpointCredentialResolver credentialResolver;
    private final ProviderExchangeClient exchangeClient;
    private final OpenAiStreamDecoder decoder;

    public OpenAiChatCompletionsAdapter(
            ObjectMapper objectMapper,
            OutboundTargetPolicy targetPolicy,
            EndpointCredentialResolver credentialResolver,
            ProviderExchangeClient exchangeClient
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper 不能为空。");
        this.targetPolicy = Objects.requireNonNull(targetPolicy, "出站目标策略不能为空。");
        this.credentialResolver = Objects.requireNonNull(credentialResolver, "凭据解析器不能为空。");
        this.exchangeClient = Objects.requireNonNull(exchangeClient, "服务商交换客户端不能为空。");
        this.decoder = new OpenAiStreamDecoder(objectMapper);
    }

    public Flux<InferenceEvent> infer(ChatInferenceCommand command, InferenceTarget target) {
        Objects.requireNonNull(command, "推理命令不能为空。");
        Objects.requireNonNull(target, "推理目标不能为空。");
        return Flux.defer(() -> {
            try {
                validate(command, target);
                URI finalUri = chatCompletionsUri(target.endpointBaseUrl());
                byte[] body = requestBody(command, target);
                long deadlineNanos = System.nanoTime()
                        + Duration.ofSeconds(target.endpointRequestTimeoutSeconds()).toNanos();
                Flux<InferenceEvent> workflow = Flux.concat(
                        Flux.just(new InferenceEvent.Start(UUID.randomUUID().toString())),
                        executeWithRetry(command, target, finalUri, body, deadlineNanos, 0));
                return onlyFirstTerminal(workflow
                        .takeUntilOther(deadlineSignal(remainingDuration(deadlineNanos)))
                        .onErrorResume(RequestDeadlineExceededException.class,
                                ignored -> Flux.just(error(command, ErrorCode.CONNECTION_FAILED, false))));
            } catch (LocalValidationException | TargetPolicyException exception) {
                return Flux.just(error(command, ErrorCode.TARGET_REJECTED, false));
            } catch (RuntimeException exception) {
                return Flux.just(error(command, ErrorCode.INTERNAL_DEPENDENCY_ERROR, false));
            }
        });
    }

    private Flux<InferenceEvent> executeWithRetry(
            ChatInferenceCommand command,
            InferenceTarget target,
            URI finalUri,
            byte[] body,
            long deadlineNanos,
            int retryCount
    ) {
        Duration remaining = remainingDuration(deadlineNanos);
        if (remaining == null) {
            return Flux.error(new RequestDeadlineExceededException());
        }
        return executeAttempt(command, target, finalUri, body, remaining)
                .onErrorResume(failure -> {
                    AttemptFailure mapped = mapBeforeStart(failure);
                    if (mapped.retryAllowed && retryCount < MAX_RETRIES) {
                        return executeWithRetry(command, target, finalUri, body, deadlineNanos, retryCount + 1);
                    }
                    return Flux.just(error(command, mapped.code, mapped.retryable));
                });
    }

    private Flux<InferenceEvent> executeAttempt(
            ChatInferenceCommand command,
            InferenceTarget target,
            URI finalUri,
            byte[] body,
            Duration remaining
    ) {
        return credentialResolver.withCredentialFlux(target, credential -> Flux.defer(() -> {
            return Mono.fromCallable(() -> targetPolicy.validate(finalUri))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(validatedTarget -> exchange(command, target, body, remaining, credential, validatedTarget));
        }));
    }

    private Flux<InferenceEvent> exchange(
            ChatInferenceCommand command,
            InferenceTarget target,
            byte[] body,
            Duration remaining,
            io.github.yanhuo218.autumnwind.inference.credentials.ResolvedCredential credential,
            io.github.yanhuo218.autumnwind.inference.security.ValidatedTarget validatedTarget
    ) {
            ProviderRequest request = new ProviderRequest(credential.apiKey(), body);
            ProviderExchangeLimits limits = ProviderExchangeLimits
                    .forTargetTimeoutSeconds(target.endpointRequestTimeoutSeconds());
            Flux<ProviderFrame> exchange;
            try {
                exchange = exchangeClient.exchange(validatedTarget, request, limits);
            } catch (RuntimeException exception) {
                return Flux.error(mapExchangeError(exception));
            }
            if (exchange == null) {
                return Flux.error(new ConnectionAttemptFailure());
            }
            return exchange
                    .takeUntilOther(Mono.delay(remaining)
                            .then(Mono.error(new RequestDeadlineExceededException())))
                    .onErrorMap(OpenAiChatCompletionsAdapter::mapExchangeError)
                    .switchOnFirst((signal, frames) -> {
                        if (signal.hasError()) {
                            return Flux.error(Objects.requireNonNull(signal.getThrowable()));
                        }
                        if (!signal.hasValue()) {
                            return Flux.error(new AttemptFailure(ErrorCode.PROVIDER_RESPONSE_INVALID, false, false));
                        }
                        ProviderFrame first = Objects.requireNonNull(signal.get());
                        if (first.status() < 200 || first.status() >= 300) {
                            return Flux.error(statusFailure(first.status()));
                        }
                        Flux<InferenceEvent> decoded = command.stream()
                                ? decoder.decode(frames, target.capabilities().reasoning())
                                : decoder.decodeNonStreaming(frames, target.capabilities().reasoning());
                        return decoded.onErrorResume(error -> Flux.just(mapAfterStart(command, error)));
                    });
    }

    private static Flux<InferenceEvent> onlyFirstTerminal(Flux<InferenceEvent> events) {
        return Flux.defer(() -> {
            AtomicBoolean terminal = new AtomicBoolean();
            return events.<InferenceEvent>handle((event, sink) -> {
                        if (!terminal.get()) {
                            sink.next(event);
                            if (event instanceof InferenceEvent.Error || event instanceof InferenceEvent.Done) {
                                terminal.set(true);
                            }
                        }
                    })
                    .takeUntil(event -> event instanceof InferenceEvent.Error || event instanceof InferenceEvent.Done);
        });
    }

    private byte[] requestBody(ChatInferenceCommand command, InferenceTarget target) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", target.providerModelId());
            ArrayNode messages = root.putArray("messages");
            if (command.systemPrompt() != null) {
                addMessage(messages, "system", command.systemPrompt());
            }
            for (ChatInferenceCommand.Message message : command.messages()) {
                addMessage(messages, message.role(), message.content());
            }
            if (command.temperature() != null) {
                root.put("temperature", command.temperature());
            }
            if (command.maxOutputTokens() != null) {
                root.put("max_tokens", command.maxOutputTokens());
            }
            root.put("stream", command.stream());
            if (command.stream()) {
                root.putObject("stream_options").put("include_usage", true);
            }
            return objectMapper.writeValueAsBytes(root);
        } catch (RuntimeException exception) {
            throw new RequestMappingException();
        }
    }

    private static void addMessage(ArrayNode messages, String role, String content) {
        messages.addObject().put("role", role).put("content", content);
    }

    private static void validate(ChatInferenceCommand command, InferenceTarget target) {
        InferenceTarget.Capabilities capabilities = target.capabilities();
        require(command.tenantId().equals(target.ownerUserId()));
        require(command.modelId().equals(target.modelId()));
        require("OPENAI_COMPATIBLE".equals(target.endpointProtocol()));
        require("CHAT_COMPLETIONS".equals(capabilities.interfaceType()));
        require(capabilities.inputModalities().contains("TEXT"));
        require("TEXT".equals(capabilities.outputModality()));
        require(!command.messages().isEmpty());
        for (ChatInferenceCommand.Message message : command.messages()) {
            require(message != null
                    && MESSAGE_ROLES.contains(message.role())
                    && message.content() != null
                    && !message.content().isBlank());
        }
        if (command.systemPrompt() != null) {
            require(!command.systemPrompt().isBlank() && capabilities.systemPrompt());
        }
        if (command.stream()) {
            require(capabilities.streaming());
        }
        if (command.temperature() != null) {
            require(Double.isFinite(command.temperature())
                    && command.temperature() >= 0
                    && command.temperature() <= 2);
        }
        if (command.maxOutputTokens() != null) {
            require(command.maxOutputTokens() > 0
                    && command.maxOutputTokens() <= capabilities.maxOutputLength());
        }
    }

    private static URI chatCompletionsUri(URI base) {
        try {
            String scheme = Objects.requireNonNull(base.getScheme());
            String authority = Objects.requireNonNull(base.getRawAuthority());
            String path = base.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            } else if (!path.endsWith("/")) {
                path += "/";
            }
            path += "chat/completions";
            return URI.create(scheme + "://" + authority + path);
        } catch (RuntimeException exception) {
            throw new LocalValidationException();
        }
    }

    private static AttemptFailure statusFailure(int status) {
        return switch (status) {
            case 401, 403 -> new AttemptFailure(ErrorCode.PROVIDER_AUTHENTICATION_FAILED, false, false);
            case 400, 404, 422 -> new AttemptFailure(ErrorCode.PROVIDER_ERROR, false, false);
            case 429 -> new AttemptFailure(ErrorCode.PROVIDER_RATE_LIMITED, true, true);
            case 502, 503, 504 -> new AttemptFailure(ErrorCode.PROVIDER_UNAVAILABLE, true, true);
            default -> new AttemptFailure(ErrorCode.PROVIDER_ERROR, false, false);
        };
    }

    private static Throwable mapExchangeError(Throwable error) {
        if (error instanceof AttemptFailure
                || error instanceof TargetPolicyException
                || error instanceof ProviderExchangeClient.ResponseLimitExceededException
                || error instanceof RequestDeadlineExceededException) {
            return error;
        }
        return new ConnectionAttemptFailure();
    }

    private static AttemptFailure mapBeforeStart(Throwable failure) {
        if (failure instanceof AttemptFailure attemptFailure) {
            return attemptFailure;
        }
        if (failure instanceof TargetPolicyException) {
            return new AttemptFailure(ErrorCode.TARGET_REJECTED, false, false);
        }
        if (failure instanceof ProviderExchangeClient.ResponseLimitExceededException) {
            return new AttemptFailure(ErrorCode.PROVIDER_RESPONSE_INVALID, false, false);
        }
        if (failure instanceof RequestDeadlineExceededException) {
            return new AttemptFailure(ErrorCode.CONNECTION_FAILED, false, false);
        }
        return new AttemptFailure(ErrorCode.INTERNAL_DEPENDENCY_ERROR, false, false);
    }

    private static InferenceEvent.Error mapAfterStart(ChatInferenceCommand command, Throwable failure) {
        if (failure instanceof ProviderResponseException
                || failure instanceof ProviderExchangeClient.ResponseLimitExceededException) {
            return error(command, ErrorCode.PROVIDER_RESPONSE_INVALID, false);
        }
        return error(command, ErrorCode.CONNECTION_FAILED, false);
    }

    private static InferenceEvent.Error error(ChatInferenceCommand command, ErrorCode code, boolean retryable) {
        return new InferenceEvent.Error(code, command.correlationId(), retryable);
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new LocalValidationException();
        }
    }

    private static Duration remainingDuration(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        return remainingNanos > 0 ? Duration.ofNanos(remainingNanos) : null;
    }

    private static Mono<Void> deadlineSignal(Duration remaining) {
        if (remaining == null) {
            return Mono.error(new RequestDeadlineExceededException());
        }
        return Mono.delay(remaining).then(Mono.error(new RequestDeadlineExceededException()));
    }

    private static class SafeAdapterException extends RuntimeException {

        @Override
        public String toString() {
            return getClass().getSimpleName();
        }
    }

    private static final class LocalValidationException extends SafeAdapterException {
    }

    private static final class RequestMappingException extends SafeAdapterException {
    }

    private static class AttemptFailure extends SafeAdapterException {

        private final ErrorCode code;
        private final boolean retryable;
        private final boolean retryAllowed;

        private AttemptFailure(ErrorCode code, boolean retryable, boolean retryAllowed) {
            this.code = code;
            this.retryable = retryable;
            this.retryAllowed = retryAllowed;
        }
    }

    private static final class ConnectionAttemptFailure extends AttemptFailure {

        private ConnectionAttemptFailure() {
            super(ErrorCode.CONNECTION_FAILED, true, true);
        }
    }

    private static final class RequestDeadlineExceededException extends SafeAdapterException {
    }
}
