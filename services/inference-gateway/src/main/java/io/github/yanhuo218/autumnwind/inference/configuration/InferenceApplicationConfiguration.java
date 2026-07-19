package io.github.yanhuo218.autumnwind.inference.configuration;

import io.github.yanhuo218.autumnwind.inference.application.ChatInferenceService;
import io.github.yanhuo218.autumnwind.inference.chat.OpenAiChatCompletionsAdapter;
import io.github.yanhuo218.autumnwind.inference.credentials.EndpointCredentialResolver;
import io.github.yanhuo218.autumnwind.inference.registry.InferenceTargetClient;
import io.github.yanhuo218.autumnwind.inference.security.HostResolver;
import io.github.yanhuo218.autumnwind.inference.security.JdkHostResolver;
import io.github.yanhuo218.autumnwind.inference.security.OutboundTargetPolicy;
import io.github.yanhuo218.autumnwind.inference.security.PublicAddressPolicy;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderExchangeClient;
import io.github.yanhuo218.autumnwind.inference.transport.ReactorNettyProviderExchangeClient;
import io.github.yanhuo218.autumnwind.security.secrets.AesGcmSecretStore;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({InferenceHttpProperties.class, InferenceSecretStoreProperties.class})
public class InferenceApplicationConfiguration {

    @Bean
    ObjectMapper strictObjectMapper() {
        return new ObjectMapper().rebuild()
                .enable(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
    }

    @Bean
    HostResolver jdkHostResolver() {
        return new JdkHostResolver();
    }

    @Bean
    PublicAddressPolicy publicAddressPolicy() {
        return new PublicAddressPolicy();
    }

    @Bean
    OutboundTargetPolicy outboundTargetPolicy(HostResolver jdkHostResolver, PublicAddressPolicy publicAddressPolicy) {
        return new OutboundTargetPolicy(jdkHostResolver, publicAddressPolicy);
    }

    @Bean
    SecretStore inferenceSecretStore(InferenceSecretStoreProperties properties) {
        return AesGcmSecretStore.fromBase64File(properties.masterKeyFile(), properties.keyId());
    }

    @Bean
    EndpointCredentialResolver endpointCredentialResolver(SecretStore inferenceSecretStore) {
        return new EndpointCredentialResolver(inferenceSecretStore);
    }

    @Bean
    ProviderExchangeClient providerExchangeClient(OutboundTargetPolicy outboundTargetPolicy) {
        return new ReactorNettyProviderExchangeClient(outboundTargetPolicy);
    }

    @Bean
    OpenAiChatCompletionsAdapter openAiChatCompletionsAdapter(
            ObjectMapper strictObjectMapper,
            OutboundTargetPolicy outboundTargetPolicy,
            EndpointCredentialResolver endpointCredentialResolver,
            ProviderExchangeClient providerExchangeClient
    ) {
        return new OpenAiChatCompletionsAdapter(
                strictObjectMapper, outboundTargetPolicy, endpointCredentialResolver, providerExchangeClient);
    }

    @Bean
    ChatInferenceService chatInferenceService(
            InferenceTargetClient inferenceTargetClient,
            OpenAiChatCompletionsAdapter openAiChatCompletionsAdapter
    ) {
        return new ChatInferenceService(inferenceTargetClient, openAiChatCompletionsAdapter);
    }
}
