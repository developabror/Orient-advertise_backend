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
import java.time.Instant;

/**
 * One short-lived rendezvous between an operator browser and a device, brokered by an external
 * relay. The backend mints it, pushes it to the device, and records the outcome — <b>it never
 * carries media</b>.
 *
 * <p><b>Why not {@code remote_action}:</b> that table's 5-minute {@code DEFAULT_TIMEOUT},
 * one-PENDING-per-type rule, once-and-terminal confirm and {@code RemoteActionExpirationJob}
 * would all fight a long-lived session. {@code remote_action} <i>initiates</i> things; this
 * entity <i>holds</i> one.
 *
 * <p><b>Lifecycle</b> — illegal transitions are rejected here, in the entity, not in the service,
 * so no caller can write a state machine violation to the database:
 * <pre>
 *   PENDING ──(device acks READY)──&gt; ACTIVE ──(operator stop / device ENDED)──&gt; ENDED
 *      │                               │
 *      ├──(device acks FAILED)─────────┴──────────────────────────────────────&gt; FAILED
 *      └──(expires_at passes, no ack)─────────────────────────────────────────&gt; EXPIRED
 * </pre>
 *
 * <p>{@code expiresAt} is a hard ceiling the <b>device</b> enforces on its own clock; the
 * server-side expiration job is only a janitor (see {@code RemoteSessionExpirationJob}).
 *
 * <p>Note there is deliberately <b>no</b> {@code getDeviceId()} convenience getter: a
 * non-persistent getter of that name would be picked up by Spring Data's derived-query parser
 * in place of the {@code device.id} path and blow up at repository-factory startup — the exact
 * trap documented on {@code DeviceRepository.findBySyncGroupIdAndDeletedAtIsNull}.
 */
@Entity
@Table(name = "remote_session")
public class RemoteSession {

    /** {@code "rs_"} + 16 random bytes hex — never derived from the device id (enumeration guard). */
    public static final String SESSION_KEY_PREFIX = "rs_";

    public static final String END_REASON_OPERATOR_STOP = "OPERATOR_STOP";
    public static final String END_REASON_DEVICE_ENDED = "DEVICE_ENDED";
    public static final String END_REASON_EXPIRED = "EXPIRED";
    public static final String END_REASON_DEVICE_FAILED = "DEVICE_FAILED";

    /** Column widths mirror V43 — values are truncated to fit rather than failing the write. */
    private static final int END_REASON_MAX = 64;

    public enum Status { PENDING, ACTIVE, ENDED, FAILED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_key", nullable = false, length = 64)
    private String sessionKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "view_only", nullable = false)
    private boolean viewOnly;

    @Column(name = "issued_by", nullable = false, length = 100)
    private String issuedBy;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "end_reason", length = END_REASON_MAX)
    private String endReason;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "device_width")
    private Integer deviceWidth;

    @Column(name = "device_height")
    private Integer deviceHeight;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RemoteSession() {
    }

    public RemoteSession(String sessionKey, Device device, boolean viewOnly, String issuedBy,
                          Instant expiresAt) {
        this.sessionKey = sessionKey;
        this.device = device;
        this.viewOnly = viewOnly;
        this.issuedBy = issuedBy;
        this.status = Status.PENDING;
        this.issuedAt = Instant.now();
        this.expiresAt = expiresAt;
        this.updatedAt = this.issuedAt;
    }

    public Long getId() { return id; }
    public String getSessionKey() { return sessionKey; }
    public Device getDevice() { return device; }
    public Status getStatus() { return status; }
    public boolean isViewOnly() { return viewOnly; }
    public String getIssuedBy() { return issuedBy; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public String getEndReason() { return endReason; }
    public String getError() { return error; }
    public Integer getDeviceWidth() { return deviceWidth; }
    public Integer getDeviceHeight() { return deviceHeight; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** {@code ENDED}, {@code FAILED} and {@code EXPIRED} are final — nothing moves out of them. */
    public boolean isTerminal() {
        return status == Status.ENDED || status == Status.FAILED || status == Status.EXPIRED;
    }

    /** True once the wall clock has passed the hard ceiling, regardless of the recorded status. */
    public boolean isPastExpiry(Instant now) {
        return now.isAfter(expiresAt);
    }

    /**
     * Device acked {@code READY}. {@code PENDING → ACTIVE}; a repeat ack while already
     * {@code ACTIVE} is idempotent and simply refreshes the reported dimensions (a device
     * retrying its ack after a lost response must not be punished with a 409).
     * Non-positive dimensions are stored as {@code null} rather than nonsense.
     */
    public void markActive(Integer width, Integer height) {
        requireNotTerminal("activate");
        this.status = Status.ACTIVE;
        if (this.startedAt == null) {
            this.startedAt = Instant.now();
        }
        this.deviceWidth = positiveOrNull(width);
        this.deviceHeight = positiveOrNull(height);
        touch();
    }

    /** Operator stop, device-reported end, or any other clean teardown. */
    public void markEnded(String reason) {
        requireNotTerminal("end");
        this.status = Status.ENDED;
        this.endedAt = Instant.now();
        this.endReason = truncate(reason, END_REASON_MAX);
        touch();
    }

    /** Device could not start (no root, scrcpy missing, relay unreachable, …). */
    public void markFailed(String error) {
        requireNotTerminal("fail");
        this.status = Status.FAILED;
        this.endedAt = Instant.now();
        this.endReason = END_REASON_DEVICE_FAILED;
        this.error = error;
        touch();
    }

    /**
     * Janitor sweep past {@code expires_at}. Distinct from {@link #markEnded(String)} because
     * "nobody ever acked / it ran out of time" and "an operator stopped it" are different
     * operational signals in the audit trail.
     */
    public void markExpired() {
        requireNotTerminal("expire");
        this.status = Status.EXPIRED;
        this.endedAt = Instant.now();
        this.endReason = END_REASON_EXPIRED;
        touch();
    }

    private void requireNotTerminal(String transition) {
        if (isTerminal()) {
            throw new IllegalStateException(
                    "Remote session %s is already %s and cannot %s.".formatted(
                            sessionKey, status, transition));
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static Integer positiveOrNull(Integer value) {
        return (value == null || value <= 0) ? null : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        var trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
