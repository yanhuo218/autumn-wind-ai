package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence;

import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointProtocol;
import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointSettings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "endpoints", schema = "model_registry")
public class EndpointEntity {

    @Id
    private UUID id;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "base_url", nullable = false, length = 2048)
    private String baseUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EndpointProtocol protocol;

    @Column(name = "request_timeout_seconds", nullable = false)
    private int requestTimeoutSeconds;

    @Column(nullable = false)
    private boolean enabled;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_credential_id")
    private EndpointCredentialEntity currentCredential;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EndpointEntity() {
    }

    public static EndpointEntity create(
            UUID id,
            UUID ownerUserId,
            EndpointSettings settings,
            Instant now
    ) {
        Objects.requireNonNull(settings, "端点设置不能为空。");
        EndpointEntity entity = new EndpointEntity();
        entity.id = Objects.requireNonNull(id, "端点标识不能为空。");
        entity.ownerUserId = Objects.requireNonNull(ownerUserId, "端点所有者不能为空。");
        entity.displayName = settings.displayName();
        entity.baseUrl = settings.baseUrl().toASCIIString();
        entity.protocol = settings.protocol();
        entity.requestTimeoutSeconds = Math.toIntExact(settings.requestTimeout().toSeconds());
        entity.enabled = settings.enabled();
        entity.createdAt = Objects.requireNonNull(now, "端点创建时间不能为空。");
        entity.updatedAt = now;
        return entity;
    }

    public void attachCredential(EndpointCredentialEntity credential, Instant now) {
        Objects.requireNonNull(credential, "端点凭据不能为空。");
        if (!id.equals(credential.endpointId())) {
            throw new IllegalArgumentException("端点凭据不属于当前端点。");
        }
        currentCredential = credential;
        updatedAt = Objects.requireNonNull(now, "端点更新时间不能为空。");
    }

    public UUID id() {
        return id;
    }

    public UUID ownerUserId() {
        return ownerUserId;
    }

    public String displayName() {
        return displayName;
    }

    public URI baseUrl() {
        return URI.create(baseUrl);
    }

    public EndpointProtocol protocol() {
        return protocol;
    }

    public int requestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public boolean enabled() {
        return enabled;
    }

    public EndpointCredentialEntity currentCredential() {
        return currentCredential;
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
