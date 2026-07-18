package io.github.yanhuo218.autumnwind.inference.chat;

import io.github.yanhuo218.autumnwind.inference.transport.ProviderFrame;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class OpenAiStreamDecoder {

    private final ObjectMapper objectMapper;

    public OpenAiStreamDecoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper 不能为空。");
    }

    public Flux<InferenceEvent> decode(Flux<ProviderFrame> frames, boolean reasoningSupported) {
        Objects.requireNonNull(frames, "服务商响应帧不能为空。");
        return Flux.defer(() -> {
            SseState state = new SseState(reasoningSupported);
            return frames.concatMap(frame -> Flux.fromIterable(state.accept(frame.data())))
                    .concatWith(Flux.defer(state::finish));
        });
    }

    public Flux<InferenceEvent> decodeNonStreaming(Flux<ProviderFrame> frames, boolean reasoningSupported) {
        Objects.requireNonNull(frames, "服务商响应帧不能为空。");
        return frames.collect(ByteArrayOutputStream::new, (body, frame) -> body.writeBytes(frame.data()))
                .flatMapMany(body -> Flux.fromIterable(parseNonStreaming(body.toByteArray(), reasoningSupported)));
    }

    private List<InferenceEvent> parseNonStreaming(byte[] bytes, boolean reasoningSupported) {
        try {
            if (bytes.length == 0) {
                throw invalidResponse();
            }
            JsonNode root = objectMapper.readTree(decodeUtf8(bytes));
            if (root == null || !root.isObject()) {
                throw invalidResponse();
            }
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty() || !choices.get(0).isObject()) {
                throw invalidResponse();
            }
            JsonNode firstChoice = choices.get(0);
            JsonNode message = firstChoice.get("message");
            if (message == null || !message.isObject() || !message.has("content")) {
                throw invalidResponse();
            }

            List<InferenceEvent> events = new ArrayList<>();
            if (reasoningSupported) {
                addReasoning(events, message);
            }
            addText(events, message.get("content"));
            addUsage(events, root.get("usage"));
            events.add(new InferenceEvent.Done(nullableText(firstChoice.get("finish_reason"))));
            return events;
        } catch (ProviderResponseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidResponse();
        }
    }

    private List<InferenceEvent> parseSseData(byte[] bytes, boolean reasoningSupported, SseState state) {
        try {
            String data = decodeUtf8(bytes);
            if ("[DONE]".equals(data.trim())) {
                state.done = true;
                return List.of(new InferenceEvent.Done(state.finishReason));
            }

            JsonNode root = objectMapper.readTree(data);
            if (root == null || !root.isObject()) {
                throw invalidResponse();
            }
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray()) {
                throw invalidResponse();
            }

            List<InferenceEvent> events = new ArrayList<>();
            for (JsonNode choice : choices) {
                if (!choice.isObject()) {
                    throw invalidResponse();
                }
                JsonNode delta = choice.get("delta");
                if (delta != null && !delta.isNull()) {
                    if (!delta.isObject()) {
                        throw invalidResponse();
                    }
                    if (reasoningSupported) {
                        addReasoning(events, delta);
                    }
                    addText(events, delta.get("content"));
                }
                JsonNode finishReason = choice.get("finish_reason");
                if (finishReason != null && !finishReason.isNull()) {
                    state.finishReason = requiredTextNode(finishReason);
                }
            }
            addUsage(events, root.get("usage"));
            return events;
        } catch (ProviderResponseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidResponse();
        }
    }

    private static void addReasoning(List<InferenceEvent> events, JsonNode container) {
        JsonNode reasoningContent = container.get("reasoning_content");
        JsonNode reasoning = reasoningContent == null || reasoningContent.isNull()
                ? container.get("reasoning")
                : reasoningContent;
        String value = optionalText(reasoning);
        if (value != null && !value.isEmpty()) {
            events.add(new InferenceEvent.Reasoning(value));
        }
    }

    private static void addText(List<InferenceEvent> events, JsonNode content) {
        String value = optionalText(content);
        if (value != null && !value.isEmpty()) {
            events.add(new InferenceEvent.TextDelta(value));
        }
    }

    private static void addUsage(List<InferenceEvent> events, JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return;
        }
        if (!usage.isObject()) {
            throw invalidResponse();
        }
        events.add(new InferenceEvent.Usage(
                nullableToken(usage.get("prompt_tokens")),
                nullableToken(usage.get("completion_tokens")),
                nullableToken(usage.get("total_tokens"))));
    }

    private static Integer nullableToken(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalidResponse();
        }
        return value.intValue();
    }

    private static String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : requiredTextNode(value);
    }

    private static String optionalText(JsonNode value) {
        return value == null || value.isNull() ? null : requiredTextNode(value);
    }

    private static String requiredTextNode(JsonNode value) {
        if (!value.isString()) {
            throw invalidResponse();
        }
        return value.stringValue();
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalidResponse();
        }
    }

    private static ProviderResponseException invalidResponse() {
        return new ProviderResponseException();
    }

    private final class SseState {

        private final boolean reasoningSupported;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        private boolean done;
        private String finishReason;

        private SseState(boolean reasoningSupported) {
            this.reasoningSupported = reasoningSupported;
        }

        private List<InferenceEvent> accept(byte[] bytes) {
            List<InferenceEvent> events = new ArrayList<>();
            for (byte value : bytes) {
                if (done) {
                    break;
                }
                if (value == '\n') {
                    processLine(events);
                    line.reset();
                } else {
                    line.write(value);
                }
            }
            return events;
        }

        private void processLine(List<InferenceEvent> events) {
            byte[] lineBytes = line.toByteArray();
            int length = lineBytes.length;
            if (length > 0 && lineBytes[length - 1] == '\r') {
                length--;
            }
            if (length == 0) {
                if (data.size() > 0) {
                    events.addAll(parseSseData(data.toByteArray(), reasoningSupported, this));
                    data.reset();
                }
                return;
            }
            if (length < 5
                    || lineBytes[0] != 'd'
                    || lineBytes[1] != 'a'
                    || lineBytes[2] != 't'
                    || lineBytes[3] != 'a'
                    || lineBytes[4] != ':') {
                return;
            }
            int offset = length > 5 && lineBytes[5] == ' ' ? 6 : 5;
            if (data.size() > 0) {
                data.write('\n');
            }
            data.write(lineBytes, offset, length - offset);
        }

        private Flux<InferenceEvent> finish() {
            return done ? Flux.empty() : Flux.error(invalidResponse());
        }
    }
}

final class ProviderResponseException extends RuntimeException {

    ProviderResponseException() {
        super("服务商响应无效。");
    }
}
