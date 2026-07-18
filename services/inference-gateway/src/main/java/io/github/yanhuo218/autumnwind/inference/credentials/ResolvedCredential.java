package io.github.yanhuo218.autumnwind.inference.credentials;

import java.util.Arrays;
import java.util.Objects;

public final class ResolvedCredential implements AutoCloseable {

    private final byte[] apiKey;

    public ResolvedCredential(byte[] apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "API Key 不能为空。");
    }

    public byte[] apiKey() {
        return apiKey;
    }

    @Override
    public void close() {
        Arrays.fill(apiKey, (byte) 0);
    }

    @Override
    public String toString() {
        return "ResolvedCredential[apiKey=<REDACTED>]";
    }
}
