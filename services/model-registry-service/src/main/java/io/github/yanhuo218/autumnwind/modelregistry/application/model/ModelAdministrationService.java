package io.github.yanhuo218.autumnwind.modelregistry.application.model;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ModelRegistryApplicationException;
import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ModelRegistryErrorCode;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelInterfaceType;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.EndpointRepository;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.ModelEntity;
import io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence.ModelRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

@Service
public class ModelAdministrationService {

    private final EndpointRepository endpointRepository;
    private final ModelRepository modelRepository;
    private final Clock clock;

    public ModelAdministrationService(
            EndpointRepository endpointRepository,
            ModelRepository modelRepository,
            Clock clock
    ) {
        this.endpointRepository = Objects.requireNonNull(endpointRepository, "端点仓库不能为空。");
        this.modelRepository = Objects.requireNonNull(modelRepository, "模型仓库不能为空。");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空。");
    }

    @Transactional
    public ModelView create(CreateModelCommand command) {
        Objects.requireNonNull(command, "创建模型命令不能为空。");
        EndpointEntity endpoint = endpointRepository
                .findByIdAndOwnerUserId(command.endpointId(), command.ownerUserId())
                .orElseThrow(ModelAdministrationService::endpointNotFound);
        UUID modelId = UUID.randomUUID();
        validateConflicts(
                command.ownerUserId(),
                command.endpointId(),
                command.providerModelId(),
                command.capabilities().interfaceType(),
                command.defaultModel(),
                modelId
        );
        ModelEntity model = ModelEntity.create(
                modelId,
                endpoint,
                command.providerModelId(),
                command.displayName(),
                command.capabilities(),
                command.enabled(),
                command.defaultModel(),
                clock.instant()
        );
        return ModelView.from(saveModel(model));
    }

    @Transactional(readOnly = true)
    public ModelView get(UUID ownerUserId, UUID modelId) {
        return ModelView.from(ownedModel(ownerUserId, modelId));
    }

    @Transactional(readOnly = true)
    public List<ModelView> list(UUID ownerUserId) {
        Objects.requireNonNull(ownerUserId, "模型所有者不能为空。");
        return modelRepository.findAllByOwnerUserIdOrderByCreatedAtAsc(ownerUserId).stream()
                .map(ModelView::from)
                .toList();
    }

    @Transactional
    public ModelView update(UpdateModelCommand command) {
        Objects.requireNonNull(command, "更新模型命令不能为空。");
        ModelEntity model = ownedModel(command.ownerUserId(), command.modelId());
        if (model.version() != command.expectedVersion()) {
            throw versionConflict();
        }
        validateConflicts(
                command.ownerUserId(),
                model.endpointId(),
                command.providerModelId(),
                command.capabilities().interfaceType(),
                command.defaultModel(),
                model.id()
        );
        model.update(
                command.providerModelId(),
                command.displayName(),
                command.capabilities(),
                command.enabled(),
                command.defaultModel(),
                clock.instant()
        );
        return ModelView.from(saveModel(model));
    }

    private ModelEntity ownedModel(UUID ownerUserId, UUID modelId) {
        Objects.requireNonNull(ownerUserId, "模型所有者不能为空。");
        Objects.requireNonNull(modelId, "模型标识不能为空。");
        return modelRepository.findByIdAndOwnerUserId(modelId, ownerUserId)
                .orElseThrow(() -> new ModelRegistryApplicationException(
                        ModelRegistryErrorCode.MODEL_NOT_FOUND,
                        "模型不存在。"
                ));
    }

    private void validateConflicts(
            UUID ownerUserId,
            UUID endpointId,
            String providerModelId,
            ModelInterfaceType interfaceType,
            boolean defaultModel,
            UUID excludedId
    ) {
        if (modelRepository.existsByOwnerUserIdAndEndpointIdAndProviderModelIdAndIdNot(
                ownerUserId,
                endpointId,
                providerModelId,
                excludedId
        )) {
            throw modelConflict();
        }
        if (defaultModel && modelRepository.existsByOwnerUserIdAndInterfaceTypeAndDefaultModelTrueAndIdNot(
                ownerUserId,
                interfaceType,
                excludedId
        )) {
            throw defaultModelConflict();
        }
    }

    private ModelEntity saveModel(ModelEntity model) {
        try {
            return modelRepository.saveAndFlush(model);
        } catch (OptimisticLockingFailureException exception) {
            throw new ModelRegistryApplicationException(
                    ModelRegistryErrorCode.MODEL_VERSION_CONFLICT,
                    "模型已被其他请求修改。",
                    exception
            );
        } catch (DataIntegrityViolationException exception) {
            String constraintName = constraintName(exception);
            if ("models_owner_endpoint_provider_unique".equals(constraintName)) {
                throw modelConflict(exception);
            }
            if ("models_one_default_per_interface_idx".equals(constraintName)) {
                throw defaultModelConflict(exception);
            }
            throw exception;
        }
    }

    private static String constraintName(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                return constraintViolation.getConstraintName();
            }
            cause = cause.getCause();
        }
        return null;
    }

    private static ModelRegistryApplicationException endpointNotFound() {
        return new ModelRegistryApplicationException(
                ModelRegistryErrorCode.ENDPOINT_NOT_FOUND,
                "端点不存在。"
        );
    }

    private static ModelRegistryApplicationException modelConflict() {
        return modelConflict(null);
    }

    private static ModelRegistryApplicationException modelConflict(Throwable cause) {
        return new ModelRegistryApplicationException(
                ModelRegistryErrorCode.MODEL_CONFLICT,
                "同一端点下的服务商模型标识已存在。",
                cause
        );
    }

    private static ModelRegistryApplicationException defaultModelConflict() {
        return defaultModelConflict(null);
    }

    private static ModelRegistryApplicationException defaultModelConflict(Throwable cause) {
        return new ModelRegistryApplicationException(
                ModelRegistryErrorCode.DEFAULT_MODEL_CONFLICT,
                "同一接口类型只能设置一个默认模型。",
                cause
        );
    }

    private static ModelRegistryApplicationException versionConflict() {
        return new ModelRegistryApplicationException(
                ModelRegistryErrorCode.MODEL_VERSION_CONFLICT,
                "模型版本不匹配。"
        );
    }
}
