package io.github.yanhuo218.autumnwind.inference.transport;

import java.util.Objects;

public final class ProviderFrame {

    private final int status;
    private final byte[] data;

    public ProviderFrame(int status, byte[] data) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("HTTP 状态码不合法。");
        }
        this.status = status;
        this.data = Objects.requireNonNull(data, "响应数据不能为空。");
    }

    public int status() {
        return status;
    }

    public byte[] data() {
        return data;
    }

    @Override
    public String toString() {
        return "ProviderFrame[status=" + status + ", data=<REDACTED>]";
    }
}
