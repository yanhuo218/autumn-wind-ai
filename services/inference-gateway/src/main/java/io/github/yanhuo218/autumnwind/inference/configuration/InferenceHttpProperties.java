package io.github.yanhuo218.autumnwind.inference.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("autumn-wind.inference.http")
public record InferenceHttpProperties(int requestMaxBytes) {

    public static final int HARD_MAX_BYTES = 1_048_576;

    public InferenceHttpProperties {
        if (requestMaxBytes < 1 || requestMaxBytes > HARD_MAX_BYTES) {
            throw new IllegalArgumentException("Inference 请求体大小必须为 1 到 1 MiB。");
        }
    }
}
