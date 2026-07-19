package io.github.yanhuo218.autumnwind.inference.credentials;

import io.github.yanhuo218.autumnwind.inference.registry.InferenceTarget;
import io.github.yanhuo218.autumnwind.security.secrets.SecretContext;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStore;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class EndpointCredentialResolver {

    private static final String PURPOSE = "model-endpoint-api-key";

    private final SecretStore secretStore;

    public EndpointCredentialResolver(SecretStore secretStore) {
        this.secretStore = Objects.requireNonNull(secretStore, "SecretStore 不能为空。");
    }

    public <T> Mono<T> withCredential(
            InferenceTarget target,
            Function<ResolvedCredential, Mono<T>> action
    ) {
        Objects.requireNonNull(target, "推理目标不能为空。");
        Objects.requireNonNull(action, "凭据操作不能为空。");
        return Mono.defer(() -> Mono.usingWhen(
                resource(target),
                credential -> Mono.defer(() -> action.apply(credential)),
                EndpointCredentialResolver::close,
                (credential, error) -> close(credential),
                EndpointCredentialResolver::close
        ));
    }

    public <T> Flux<T> withCredentialFlux(
            InferenceTarget target,
            Function<ResolvedCredential, ? extends Publisher<T>> action
    ) {
        Objects.requireNonNull(target, "推理目标不能为空。");
        Objects.requireNonNull(action, "凭据操作不能为空。");
        return Flux.defer(() -> Flux.usingWhen(
                resource(target),
                credential -> Flux.defer(() -> Flux.from(action.apply(credential))),
                EndpointCredentialResolver::close,
                (credential, error) -> close(credential),
                EndpointCredentialResolver::close
        ));
    }

    private ResolvedCredential resolve(InferenceTarget target) {
        SecretContext context = new SecretContext(
                target.ownerUserId().toString(),
                PURPOSE,
                target.endpointId().toString()
        );
        return new ResolvedCredential(secretStore.decrypt(target.credential(), context));
    }

    private Mono<ResolvedCredential> resource(InferenceTarget target) {
        return Mono.create(sink -> {
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicReference<ResolvedCredential> resolved = new AtomicReference<>();
            sink.onCancel(() -> {
                cancelled.set(true);
                ResolvedCredential credential = resolved.get();
                if (credential != null) {
                    credential.close();
                }
            });
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    ResolvedCredential credential = resolve(target);
                    resolved.set(credential);
                    if (cancelled.get()) {
                        credential.close();
                    } else {
                        sink.success(credential);
                    }
                } catch (Throwable error) {
                    if (!cancelled.get()) {
                        sink.error(error);
                    }
                }
            });
        });
    }

    private static Mono<Void> close(ResolvedCredential credential) {
        return Mono.fromRunnable(credential::close);
    }
}
