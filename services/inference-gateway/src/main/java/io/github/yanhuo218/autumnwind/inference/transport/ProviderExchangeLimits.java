package io.github.yanhuo218.autumnwind.inference.transport;

import java.time.Duration;
import java.util.Objects;

public record ProviderExchangeLimits(
        Duration responseHeaderTimeout,
        Duration streamIdleTimeout,
        int maxFrameBytes,
        long maxResponseBytes
) {

    public ProviderExchangeLimits {
        responseHeaderTimeout = requirePositive(responseHeaderTimeout, "响应头超时不能为空且必须为正数。");
        streamIdleTimeout = requirePositive(streamIdleTimeout, "流空闲超时不能为空且必须为正数。");
        if (maxFrameBytes < 1 || maxResponseBytes < maxFrameBytes) {
            throw new IllegalArgumentException("服务商响应限制不合法。");
        }
    }

    public static ProviderExchangeLimits forTargetTimeoutSeconds(int seconds) {
        if (seconds < 1) {
            throw new IllegalArgumentException("端点超时必须为正数。");
        }
        Duration total = Duration.ofSeconds(seconds);
        return new ProviderExchangeLimits(
                min(total, Duration.ofSeconds(30)),
                min(total, Duration.ofSeconds(60)),
                ReactorNettyProviderExchangeClient.MAX_PROVIDER_FRAME_BYTES,
                ReactorNettyProviderExchangeClient.MAX_PROVIDER_RESPONSE_BYTES);
    }

    private static Duration requirePositive(Duration value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}
