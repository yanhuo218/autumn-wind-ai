package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence;

import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelCapabilities;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelInterfaceType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "models", schema = "model_registry")
public class ModelEntity {

    public static final int CURRENT_CAPABILITY_SCHEMA_VERSION = 1;

    @Id
    private UUID id;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private EndpointEntity endpoint;

    @Column(name = "provider_model_id", nullable = false, length = 255)
    private String providerModelId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "interface_type", nullable = false, length = 32)
    private ModelInterfaceType interfaceType;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "default_model", nullable = false)
    private boolean defaultModel;

    @Column(name = "capability_schema_version", nullable = false)
    private int capabilitySchemaVersion;

    @OneToOne(
            mappedBy = "model",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY,
            optional = false
    )
    private ModelCapabilityEntity capability;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ModelEntity() {
    }

    public static ModelEntity create(
            UUID id,
            EndpointEntity endpoint,
            String providerModelId,
            String displayName,
            ModelCapabilities capabilities,
            boolean enabled,
            boolean defaultModel,
            Instant now
    ) {
        Objects.requireNonNull(endpoint, "模型所属端点不能为空。");
        Objects.requireNonNull(capabilities, "模型能力不能为空。");
        ModelEntity entity = new ModelEntity();
        entity.id = Objects.requireNonNull(id, "模型标识不能为空。");
        entity.ownerUserId = endpoint.ownerUserId();
        entity.endpoint = endpoint;
        entity.providerModelId = Objects.requireNonNull(providerModelId, "服务商模型标识不能为空。");
        entity.displayName = Objects.requireNonNull(displayName, "模型显示名称不能为空。");
        entity.interfaceType = capabilities.interfaceType();
        entity.enabled = enabled;
        entity.defaultModel = defaultModel;
        entity.capabilitySchemaVersion = CURRENT_CAPABILITY_SCHEMA_VERSION;
        entity.createdAt = Objects.requireNonNull(now, "模型创建时间不能为空。");
        entity.updatedAt = now;
        entity.capability = ModelCapabilityEntity.create(entity, capabilities);
        return entity;
    }

    public void update(
            String providerModelId,
            String displayName,
            ModelCapabilities capabilities,
            boolean enabled,
            boolean defaultModel,
            Instant now
    ) {
        this.providerModelId = Objects.requireNonNull(providerModelId, "服务商模型标识不能为空。");
        this.displayName = Objects.requireNonNull(displayName, "模型显示名称不能为空。");
        interfaceType = Objects.requireNonNull(capabilities, "模型能力不能为空。").interfaceType();
        this.enabled = enabled;
        this.defaultModel = defaultModel;
        capability.update(capabilities);
        updatedAt = Objects.requireNonNull(now, "模型更新时间不能为空。");
    }

    public UUID id() {
        return id;
    }

    public UUID ownerUserId() {
        return ownerUserId;
    }

    public UUID endpointId() {
        return endpoint.id();
    }

    public String providerModelId() {
        return providerModelId;
    }

    public String displayName() {
        return displayName;
    }

    public ModelInterfaceType interfaceType() {
        return interfaceType;
    }

    public ModelCapabilities capabilities() {
        return capability.toCapabilities();
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean defaultModel() {
        return defaultModel;
    }

    public int capabilitySchemaVersion() {
        return capabilitySchemaVersion;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
