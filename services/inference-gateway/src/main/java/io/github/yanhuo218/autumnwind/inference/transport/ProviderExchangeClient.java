package io.github.yanhuo218.autumnwind.inference.transport;

import io.github.yanhuo218.autumnwind.inference.security.ValidatedTarget;
import reactor.core.publisher.Flux;

public interface ProviderExchangeClient {

    final class ResponseLimitExceededException extends RuntimeException {

        public ResponseLimitExceededException() {
            super("服务商响应超过资源限制。");
        }
    }

    Flux<ProviderFrame> exchange(
            ValidatedTarget target,
            ProviderRequest request,
            ProviderExchangeLimits limits);
}
