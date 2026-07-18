package io.github.yanhuo218.autumnwind.inference.transport;

import io.github.yanhuo218.autumnwind.inference.security.ValidatedTarget;
import reactor.core.publisher.Flux;

public interface ProviderExchangeClient {

    Flux<ProviderFrame> exchange(ValidatedTarget target, ProviderRequest request);
}
