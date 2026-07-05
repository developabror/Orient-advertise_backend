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
@Table(name = "entity_audit_log")
public class EntityAuditLog {

    public enum Action { CREATE, UPDATE, DELETE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String entityType;

    @Column(nullable = false)
    private Long entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;

    @Column(nullable = false, length = 100)
    private String changedBy;

    @Column(columnDefinition = "TEXT")
    private String oldValue;

    @Column(columnDefinition = "TEXT")
    private String newValue;

    @Column(nullable = false)
    private Instant changedAt;

    protected EntityAuditLog() {
    }

    public EntityAuditLog(String entityType, Long entityId, Action action,
                          String changedBy, String oldValue, String newValue) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.changedBy = changedBy;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEntityType() { return entityType; }
    public Long getEntityId() { return entityId; }
    public Action getAction() { return action; }
    public String getChangedBy() { return changedBy; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public Instant getChangedAt() { return changedAt; }
}
