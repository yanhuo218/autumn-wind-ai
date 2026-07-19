package io.github.yanhuo218.autumnwind.inference.interfaces.http;

import io.github.yanhuo218.autumnwind.inference.application.ChatInferenceService;
import io.github.yanhuo218.autumnwind.inference.application.ForbiddenActorException;
import io.github.yanhuo218.autumnwind.inference.chat.InferenceEvent;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

@RestController
public final class InferenceController {

    static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final ChatInferenceService service;
    private final ObjectMapper strictObjectMapper;

    public InferenceController(ChatInferenceService service, ObjectMapper strictObjectMapper) {
        this.service = Objects.requireNonNull(service, "推理服务不能为空。");
        this.strictObjectMapper = Objects.requireNonNull(strictObjectMapper, "严格 ObjectMapper 不能为空。");
    }

    @PostMapping(
            path = "/internal/v1/inference/chat-completions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/x-ndjson")
    public Mono<ResponseEntity<Flux<InferenceEvent>>> infer(
            JwtAuthenticationToken authentication,
            ServerWebExchange exchange
    ) {
        UUID actor = UUID.fromString(authentication.getToken().getClaimAsString("actor_user_id"));
        String correlationId = CorrelationIdWebFilter.current(exchange);
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .switchIfEmpty(Mono.error(new IllegalArgumentException("推理请求体不能为空。")))
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return new String(bytes, StandardCharsets.UTF_8);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .publishOn(Schedulers.boundedElastic())
                .map(this::parseRequest)
                .map(request -> response(request, actor, correlationId));
    }

    private ChatCompletionRequest parseRequest(String body) {
        try {
            ChatCompletionRequest request = strictObjectMapper.readValue(body, ChatCompletionRequest.class);
            validate(request);
            return request;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("推理请求格式不合法。", exception);
        }
    }

    private static void validate(ChatCompletionRequest request) {
        if (request.ownerUserId() == null || request.modelId() == null || request.generationId() == null
                || request.invocationAttemptId() == null || request.messages() == null
                || request.messages().isEmpty() || request.messages().size() > 256
                || (request.systemPrompt() != null && request.systemPrompt().isEmpty())
                || (request.temperature() != null && (!Double.isFinite(request.temperature())
                || request.temperature() < 0 || request.temperature() > 2))
                || (request.maxOutputTokens() != null
                && (request.maxOutputTokens() < 1 || request.maxOutputTokens() > 131072))) {
            throw new IllegalArgumentException("推理请求字段不合法。");
        }
        for (ChatMessageRequest message : request.messages()) {
            if (message == null || message.role() == null || message.content() == null || message.content().isBlank()) {
                throw new IllegalArgumentException("推理消息不合法。");
            }
        }
    }

    private ResponseEntity<Flux<InferenceEvent>> response(
            ChatCompletionRequest request,
            UUID actor,
            String correlationId
    ) {
        if (!request.ownerUserId().equals(actor)) {
            throw new ForbiddenActorException();
        }
        Flux<InferenceEvent> events = service.infer(request.toApplicationRequest(), actor, correlationId);
        return ResponseEntity.ok()
                .contentType(NDJSON)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(events);
    }
}
