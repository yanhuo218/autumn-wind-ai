package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointCredentialEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointCredentialRepository;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointRepository;
import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import io.github.yanhuo218.autumnwind.security.secrets.SecretContext;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStore;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStoreException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@Service
public class EndpointAdministrationService {

    private static final String SECRET_PURPOSE = "model-endpoint-api-key";

    private final EndpointRepository endpointRepository;
    private final EndpointCredentialRepository credentialRepository;
    private final SecretStore secretStore;
    private final Clock clock;

    public EndpointAdministrationService(
            EndpointRepository endpointRepository,
            EndpointCredentialRepository credentialRepository,
            SecretStore secretStore,
            Clock clock
    ) {
        this.endpointRepository = Objects.requireNonNull(endpointRepository, "端点仓库不能为空。");
        this.credentialRepository = Objects.requireNonNull(credentialRepository, "端点凭据仓库不能为空。");
        this.secretStore = Objects.requireNonNull(secretStore, "SecretStore 不能为空。");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空。");
    }

    @Transactional
    public EndpointView create(CreateEndpointCommand command) {
        Objects.requireNonNull(command, "创建端点命令不能为空。");
        Instant now = clock.instant();
        UUID endpointId = UUID.randomUUID();
        EncryptedSecret encrypted = encrypt(command.apiKey(), command.ownerUserId(), endpointId);
        EndpointEntity endpoint = EndpointEntity.create(endpointId, command.ownerUserId(), command.settings(), now);
        EndpointCredentialEntity credential = EndpointCredentialEntity.create(
                UUID.randomUUID(),
                endpointId,
                encrypted,
                now
        );

        endpointRepository.saveAndFlush(endpoint);
        EndpointCredentialEntity savedCredential = credentialRepository.save(credential);
        endpoint.attachCredential(savedCredential, now);
        return EndpointView.from(saveEndpoint(endpoint));
    }

    @Transactional(readOnly = true)
    public EndpointView get(UUID ownerUserId, UUID endpointId) {
        return EndpointView.from(ownedEndpoint(ownerUserId, endpointId));
    }

    @Transactional
    public EndpointView replaceKey(ReplaceEndpointKeyCommand command) {
        Objects.requireNonNull(command, "替换端点凭据命令不能为空。");
        EndpointEntity endpoint = ownedEndpoint(command.ownerUserId(), command.endpointId());
        if (endpoint.version() != command.expectedVersion()) {
            throw versionConflict();
        }

        EncryptedSecret encrypted = encrypt(command.apiKey(), command.ownerUserId(), command.endpointId());
        Instant now = clock.instant();
        EndpointCredentialEntity replacement = credentialRepository.save(EndpointCredentialEntity.create(
                UUID.randomUUID(),
                command.endpointId(),
                encrypted,
                now
        ));
        EndpointCredentialEntity previous = endpoint.currentCredential();
        if (previous != null) {
            previous.markReplaced(now);
        }
        endpoint.attachCredential(replacement, now);
        return EndpointView.from(saveEndpoint(endpoint));
    }

    private EndpointEntity ownedEndpoint(UUID ownerUserId, UUID endpointId) {
        Objects.requireNonNull(ownerUserId, "端点所有者不能为空。");
        Objects.requireNonNull(endpointId, "端点标识不能为空。");
        return endpointRepository.findByIdAndOwnerUserId(endpointId, ownerUserId)
                .orElseThrow(() -> new ModelRegistryApplicationException(
                        ModelRegistryErrorCode.ENDPOINT_NOT_FOUND,
                        "端点不存在。"
                ));
    }

    private EncryptedSecret encrypt(String apiKey, UUID ownerUserId, UUID endpointId) {
        byte[] plaintext = apiKey.getBytes(StandardCharsets.UTF_8);
        SecretContext context = new SecretContext(
                ownerUserId.toString(),
                SECRET_PURPOSE,
                endpointId.toString()
        );
        try {
            return secretStore.encrypt(plaintext, context);
        } catch (SecretStoreException exception) {
            throw new ModelRegistryApplicationException(
                    ModelRegistryErrorCode.ENDPOINT_SECRET_FAILURE,
                    "端点凭据加密失败。",
                    exception
            );
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private EndpointEntity saveEndpoint(EndpointEntity endpoint) {
        try {
            return endpointRepository.saveAndFlush(endpoint);
        } catch (OptimisticLockingFailureException exception) {
            throw new ModelRegistryApplicationException(
                    ModelRegistryErrorCode.ENDPOINT_VERSION_CONFLICT,
                    "端点已被其他请求修改。",
                    exception
            );
        }
    }

    private static ModelRegistryApplicationException versionConflict() {
        return new ModelRegistryApplicationException(
                ModelRegistryErrorCode.ENDPOINT_VERSION_CONFLICT,
                "端点版本不匹配。"
        );
    }
}
