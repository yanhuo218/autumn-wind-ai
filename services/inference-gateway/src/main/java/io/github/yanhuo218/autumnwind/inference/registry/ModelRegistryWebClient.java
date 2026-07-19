package io.github.yanhuo218.autumnwind.inference.registry;

import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class ModelRegistryWebClient implements InferenceTargetClient {

    private static final String RESOLUTION_PATH = "/internal/v1/model-registry/inference-target-resolutions";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final String FAILURE_MESSAGE = "模型 Registry 请求失败。";

    private final WebClient webClient;
    private final Function<UUID, Mono<String>> serviceJwtProvider;
    private final Duration timeout;

    ModelRegistryWebClient(WebClient webClient, Function<UUID, Mono<String>> serviceJwtProvider) {
        this(webClient, serviceJwtProvider, Duration.ofSeconds(30));
    }

    public ModelRegistryWebClient(
            WebClient webClient,
            Function<UUID, Mono<String>> serviceJwtProvider,
            Duration timeout
    ) {
        this.webClient = Objects.requireNonNull(webClient, "Registry WebClient 不能为空。");
        this.serviceJwtProvider = Objects.requireNonNull(serviceJwtProvider, "Service JWT 提供器不能为空。");
        this.timeout = Objects.requireNonNull(timeout, "Registry 总超时不能为空。");
    }

    @Override
    public Mono<InferenceTarget> resolve(UUID ownerUserId, UUID modelId, String correlationId) {
        Objects.requireNonNull(ownerUserId, "用户标识不能为空。");
        Objects.requireNonNull(modelId, "模型标识不能为空。");
        Objects.requireNonNull(correlationId, "关联标识不能为空。");
        ResolutionRequest request = new ResolutionRequest(ownerUserId, modelId);

        return Mono.defer(() -> serviceJwt(ownerUserId))
                .flatMap(token -> webClient.post()
                        .uri(RESOLUTION_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(CORRELATION_ID_HEADER, correlationId)
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(request)
                        .exchangeToMono(response -> readResponse(response, ownerUserId)))
                .timeout(timeout)
                .onErrorMap(error -> error instanceof RegistryClientException
                        ? error
                        : new RegistryClientException());
    }

    private Mono<String> serviceJwt(UUID ownerUserId) {
        Mono<String> token;
        try {
            token = serviceJwtProvider.apply(ownerUserId);
        } catch (RuntimeException exception) {
            return Mono.error(new RegistryClientException());
        }
        if (token == null) {
            return Mono.error(new RegistryClientException());
        }
        return token.filter(value -> !value.isBlank())
                .switchIfEmpty(Mono.error(new RegistryClientException()));
    }

    private Mono<InferenceTarget> readResponse(ClientResponse response, UUID ownerUserId) {
        if (!response.statusCode().is2xxSuccessful() || !declaresNoStore(response)) {
            return response.releaseBody().then(Mono.error(new RegistryClientException()));
        }
        return response.bodyToMono(ResolutionResponse.class)
                .switchIfEmpty(Mono.error(new RegistryClientException()))
                .map(value -> value.toTarget(ownerUserId));
    }

    private static boolean declaresNoStore(ClientResponse response) {
        return response.headers().header(HttpHeaders.CACHE_CONTROL).stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .anyMatch(value -> value.equalsIgnoreCase("no-store"));
    }

    private record ResolutionRequest(UUID ownerUserId, UUID modelId) {
    }

    private record ResolutionResponse(
            UUID modelId,
            String providerModelId,
            long modelVersion,
            UUID endpointId,
            URI endpointBaseUrl,
            String endpointProtocol,
            int endpointRequestTimeoutSeconds,
            long endpointVersion,
            CapabilitiesResponse capabilities,
            UUID credentialId,
            CredentialEnvelopeResponse credential
    ) {

        private InferenceTarget toTarget(UUID ownerUserId) {
            return new InferenceTarget(
                    ownerUserId,
                    modelId,
                    providerModelId,
                    modelVersion,
                    endpointId,
                    endpointBaseUrl,
                    endpointProtocol,
                    endpointRequestTimeoutSeconds,
                    endpointVersion,
                    capabilities.toCapabilities(),
                    credentialId,
                    credential.toEncryptedSecret()
            );
        }
    }

    private record CapabilitiesResponse(
            String interfaceType,
            Set<String> inputModalities,
            String outputModality,
            boolean streaming,
            boolean systemPrompt,
            boolean reasoning,
            int contextLength,
            int maxOutputLength
    ) {

        private InferenceTarget.Capabilities toCapabilities() {
            return new InferenceTarget.Capabilities(interfaceType, inputModalities, outputModality,
                    streaming, systemPrompt, reasoning, contextLength, maxOutputLength);
        }
    }

    private record CredentialEnvelopeResponse(
            int version,
            String keyId,
            String wrappedDataKeyNonce,
            String wrappedDataKey,
            String payloadNonce,
            String ciphertext
    ) {

        private EncryptedSecret toEncryptedSecret() {
            Base64.Decoder decoder = Base64.getDecoder();
            return new EncryptedSecret(
                    version,
                    keyId,
                    decoder.decode(wrappedDataKeyNonce),
                    decoder.decode(wrappedDataKey),
                    decoder.decode(payloadNonce),
                    decoder.decode(ciphertext)
            );
        }
    }

    private static final class RegistryClientException extends RuntimeException {

        private RegistryClientException() {
            super(FAILURE_MESSAGE);
        }
    }
}
