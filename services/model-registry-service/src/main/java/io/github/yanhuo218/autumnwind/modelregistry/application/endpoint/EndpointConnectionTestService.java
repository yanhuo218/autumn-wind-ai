package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointConnectionTestJobEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointConnectionTestJobRepository;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointCredentialEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

@Service
public class EndpointConnectionTestService {

    private final EndpointRepository endpointRepository;
    private final EndpointConnectionTestJobRepository jobRepository;
    private final Clock clock;

    public EndpointConnectionTestService(
            EndpointRepository endpointRepository,
            EndpointConnectionTestJobRepository jobRepository,
            Clock clock
    ) {
        this.endpointRepository = Objects.requireNonNull(endpointRepository, "端点仓库不能为空。");
        this.jobRepository = Objects.requireNonNull(jobRepository, "连接测试任务仓库不能为空。");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空。");
    }

    @Transactional
    public EndpointConnectionTestView enqueue(EndpointConnectionTestCommand command) {
        Objects.requireNonNull(command, "连接测试命令不能为空。");
        EndpointEntity endpoint = endpointRepository
                .findByIdAndOwnerUserId(command.endpointId(), command.ownerUserId())
                .orElseThrow(() -> new ModelRegistryApplicationException(
                        ModelRegistryErrorCode.ENDPOINT_NOT_FOUND, "端点不存在。"));
        if (endpoint.version() != command.expectedVersion()) {
            throw new ModelRegistryApplicationException(
                    ModelRegistryErrorCode.ENDPOINT_VERSION_CONFLICT, "端点版本不匹配。");
        }
        EndpointCredentialEntity credential = endpoint.currentCredential();
        if (credential == null) {
            throw new ModelRegistryApplicationException(
                    ModelRegistryErrorCode.ENDPOINT_TEST_UNAVAILABLE, "端点尚未配置可用凭据。");
        }
        EndpointConnectionTestJobEntity job = EndpointConnectionTestJobEntity.queued(
                UUID.randomUUID(),
                command.ownerUserId(),
                command.endpointId(),
                endpoint.version(),
                credential.id(),
                command.requestedByUserId(),
                command.correlationId(),
                clock.instant()
        );
        return EndpointConnectionTestView.from(jobRepository.saveAndFlush(job));
    }
}
