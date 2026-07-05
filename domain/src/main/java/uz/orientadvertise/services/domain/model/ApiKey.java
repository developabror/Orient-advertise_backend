package uz.orientadvertise.services.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "api_key")
public class ApiKey {

    public enum Status { ACTIVE, REVOKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    @Column(name = "client_name", nullable = false, length = 200)
    private String clientName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by", length = 100)
    private String revokedBy;

    protected ApiKey() {
    }

    public ApiKey(String keyHash, String keyPrefix, String clientName) {
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.clientName = clientName;
        this.status = Status.ACTIVE;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getKeyHash() { return keyHash; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getClientName() { return clientName; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokedBy() { return revokedBy; }
    public boolean isActive() { return status == Status.ACTIVE; }

    public void revoke(String by) {
        if (this.status == Status.REVOKED) {
            throw new IllegalStateException("API key " + id + " is already revoked");
        }
        this.status = Status.REVOKED;
        this.revokedAt = Instant.now();
        this.revokedBy = by;
    }
}
