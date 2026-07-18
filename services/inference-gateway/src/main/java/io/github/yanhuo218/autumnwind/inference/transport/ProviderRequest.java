package io.github.yanhuo218.autumnwind.inference.transport;

import java.util.Objects;

public final class ProviderRequest {

    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] apiKey;
    private final byte[] body;

    public ProviderRequest(byte[] apiKey, byte[] body) {
        this.apiKey = Objects.requireNonNull(apiKey, "API Key 不能为空。");
        this.body = Objects.requireNonNull(body, "请求正文不能为空。");
    }

    public byte[] apiKey() {
        return apiKey;
    }

    public byte[] body() {
        return body;
    }

    CharSequence authorizationHeader() {
        return new AuthorizationValue(apiKey);
    }

    @Override
    public String toString() {
        return "ProviderRequest[apiKey=<REDACTED>, body=<REDACTED>]";
    }

    private static final class AuthorizationValue implements CharSequence {

        private final byte[] apiKey;

        private AuthorizationValue(byte[] apiKey) {
            this.apiKey = apiKey;
        }

        @Override
        public int length() {
            return BEARER_PREFIX.length() + apiKey.length;
        }

        @Override
        public char charAt(int index) {
            Objects.checkIndex(index, length());
            if (index < BEARER_PREFIX.length()) {
                return BEARER_PREFIX.charAt(index);
            }
            return (char) (apiKey[index - BEARER_PREFIX.length()] & 0xff);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            Objects.checkFromToIndex(start, end, length());
            return new AuthorizationSlice(this, start, end);
        }

        @Override
        public String toString() {
            return "AuthorizationValue[<REDACTED>]";
        }
    }

    private record AuthorizationSlice(CharSequence source, int start, int end) implements CharSequence {

        @Override
        public int length() {
            return end - start;
        }

        @Override
        public char charAt(int index) {
            Objects.checkIndex(index, length());
            return source.charAt(start + index);
        }

        @Override
        public CharSequence subSequence(int nestedStart, int nestedEnd) {
            Objects.checkFromToIndex(nestedStart, nestedEnd, length());
            return new AuthorizationSlice(source, start + nestedStart, start + nestedEnd);
        }

        @Override
        public String toString() {
            return "AuthorizationValue[<REDACTED>]";
        }
    }
}
