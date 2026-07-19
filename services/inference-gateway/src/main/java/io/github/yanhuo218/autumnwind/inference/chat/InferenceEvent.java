package io.github.yanhuo218.autumnwind.inference.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.UUID;

public sealed interface InferenceEvent permits
        InferenceEvent.Start,
        InferenceEvent.Reasoning,
        InferenceEvent.TextDelta,
        InferenceEvent.Usage,
        InferenceEvent.Error,
        InferenceEvent.Done {

    @JsonProperty("type")
    String type();

    record Start(String attemptId) implements InferenceEvent {

        public Start {
            Objects.requireNonNull(attemptId, "尝试标识不能为空。");
            try {
                if (!UUID.fromString(attemptId).toString().equalsIgnoreCase(attemptId)) {
                    throw new IllegalArgumentException("尝试标识必须是规范 UUID。");
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("尝试标识必须是规范 UUID。");
            }
        }

        @Override
        public String type() {
            return "start";
        }
    }

    record Reasoning(String delta) implements InferenceEvent {

        public Reasoning {
            requireText(delta, "reasoning 增量不能为空。");
        }

        @Override
        public String type() {
            return "reasoning";
        }

        @Override
        public String toString() {
            return "Reasoning[delta=<REDACTED>]";
        }
    }

    record TextDelta(String delta) implements InferenceEvent {

        public TextDelta {
            requireText(delta, "文本增量不能为空。");
        }

        @Override
        public String type() {
            return "text_delta";
        }

        @Override
        public String toString() {
            return "TextDelta[delta=<REDACTED>]";
        }
    }

    record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) implements InferenceEvent {

        public Usage {
            requireNonNegative(promptTokens);
            requireNonNegative(completionTokens);
            requireNonNegative(totalTokens);
        }

        @Override
        public String type() {
            return "usage";
        }
    }

    enum ErrorCode {
        TARGET_REJECTED,
        PROVIDER_AUTHENTICATION_FAILED,
        PROVIDER_RATE_LIMITED,
        PROVIDER_UNAVAILABLE,
        PROVIDER_RESPONSE_INVALID,
        PROVIDER_ERROR,
        CONNECTION_FAILED,
        INTERNAL_DEPENDENCY_ERROR
    }

    record Error(ErrorCode code, String correlationId, boolean retryable) implements InferenceEvent {

        public Error {
            Objects.requireNonNull(code, "错误码不能为空。");
            Objects.requireNonNull(correlationId, "关联标识不能为空。");
            if (!correlationId.matches("[A-Za-z0-9._-]{16,64}")) {
                throw new IllegalArgumentException("关联标识不合法。");
            }
        }

        @Override
        public String type() {
            return "error";
        }
    }

    record Done(String finishReason) implements InferenceEvent {

        public Done {
            if (finishReason != null && finishReason.codePointCount(0, finishReason.length()) > 128) {
                throw new IllegalArgumentException("结束原因过长。");
            }
        }

        @Override
        public String type() {
            return "done";
        }

        @Override
        public String toString() {
            return "Done[finishReason=" + (finishReason == null ? "null" : "<REDACTED>") + "]";
        }
    }

    private static String requireText(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static void requireNonNegative(Integer value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException("Token 数量不能为负数。");
        }
    }
}
