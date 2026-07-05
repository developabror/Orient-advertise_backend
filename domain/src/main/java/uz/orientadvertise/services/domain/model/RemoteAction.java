package uz.orientadvertise.services.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "remote_action")
public class RemoteAction {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    public enum Status { PENDING, CONFIRMED, CONFIRMED_LATE, EXPIRED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Column(nullable = false, length = 50)
    private String actionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(nullable = false, length = 100)
    private String issuedBy;

    @Column(nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column
    private Instant confirmedAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RemoteAction() {
    }

    public RemoteAction(Device device, String actionType, String payload, String issuedBy) {
        this(device, actionType, payload, issuedBy, DEFAULT_TIMEOUT);
    }

    public RemoteAction(Device device, String actionType, String payload, String issuedBy,
                         Duration timeout) {
        this.device = device;
        this.actionType = actionType;
        this.status = Status.PENDING;
        this.payload = payload;
        this.issuedBy = issuedBy;
        this.issuedAt = Instant.now();
        this.expiresAt = this.issuedAt.plus(timeout);
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Device getDevice() { return device; }
    public String getActionType() { return actionType; }
    public Status getStatus() { return status; }
    public String getPayload() { return payload; }
    public String getResult() { return result; }
    public String getIssuedBy() { return issuedBy; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public boolean isExpired() {
        return status == Status.PENDING && Instant.now().isAfter(expiresAt);
    }

    public void confirm(String resultPayload) {
        this.status = Status.CONFIRMED;
        this.result = resultPayload;
        this.confirmedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Late SUCCESS confirmation — device acknowledged execution after the action's
     * deadline. Distinguished from {@link Status#CONFIRMED} so the operator console can
     * track on-time vs late delivery for SLO purposes.
     */
    public void confirmLate(String resultPayload) {
        this.status = Status.CONFIRMED_LATE;
        this.result = resultPayload;
        this.confirmedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markExpired() {
        this.status = Status.EXPIRED;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.result = reason;
        this.updatedAt = Instant.now();
    }
}
