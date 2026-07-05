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

@Entity
@Table(name = "incident")
public class Incident {

    @io.swagger.v3.oas.annotations.media.Schema(description = """
            Incident lifecycle state. Transitions: OPEN → ACKNOWLEDGED → RESOLVED.
            - **OPEN** — newly opened, not yet looked at
            - **ACKNOWLEDGED** — triaged but not closed
            - **RESOLVED** — closed; either manually or by automatic recovery
            """,
            enumAsRef = true)
    public enum Status { OPEN, ACKNOWLEDGED, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Column(nullable = false, length = 50)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Event.Priority priority;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    private int occurrenceCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "first_event_id", nullable = false)
    private Event firstEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_event_id", nullable = false)
    private Event lastEvent;

    @Column(nullable = false)
    private Instant openedAt;

    @Column
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by", length = 100)
    private String acknowledgedBy;

    @Column
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(nullable = false)
    private Instant updatedAt;

    public static final String SYSTEM_RESOLVER = "system";

    protected Incident() {
    }

    public Incident(Device device, String eventType, Event.Priority priority,
                    String description, Event firstEvent) {
        this.device = device;
        this.eventType = eventType;
        this.status = Status.OPEN;
        this.priority = priority;
        this.description = description;
        this.occurrenceCount = 1;
        this.firstEvent = firstEvent;
        this.lastEvent = firstEvent;
        this.openedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Device getDevice() { return device; }
    public String getEventType() { return eventType; }
    public Status getStatus() { return status; }
    public Event.Priority getPriority() { return priority; }
    public String getDescription() { return description; }
    public int getOccurrenceCount() { return occurrenceCount; }
    public Event getFirstEvent() { return firstEvent; }
    public Event getLastEvent() { return lastEvent; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public String getAcknowledgedBy() { return acknowledgedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public boolean wasManuallyResolved() {
        return resolvedAt != null && resolvedBy != null && !SYSTEM_RESOLVER.equals(resolvedBy);
    }
    public boolean isResolved() { return status == Status.RESOLVED; }
    public Instant getUpdatedAt() { return updatedAt; }

    public boolean isOpen() { return status != Status.RESOLVED; }

    public void recordRepeatOccurrence(Event newEvent) {
        this.lastEvent = newEvent;
        this.occurrenceCount++;
        this.updatedAt = Instant.now();
        if (newEvent.getPriority().ordinal() < this.priority.ordinal()) {
            this.priority = newEvent.getPriority();
        }
    }

    public void acknowledge() {
        acknowledge(null);
    }

    /**
     * Move to ACKNOWLEDGED. Allowed only from OPEN — re-acknowledging or
     * acknowledging a RESOLVED incident is rejected with {@link IllegalStateException}
     * (mapped to 409 by the global handler).
     */
    public void acknowledge(String acknowledgedBy) {
        if (this.status != Status.OPEN) {
            throw new IllegalStateException(
                    "Incident " + id + " cannot be acknowledged from status " + status);
        }
        this.status = Status.ACKNOWLEDGED;
        this.acknowledgedAt = Instant.now();
        this.acknowledgedBy = acknowledgedBy;
        this.updatedAt = Instant.now();
    }

    public void resolve() {
        resolve(null);
    }

    /**
     * Move to RESOLVED. Allowed from OPEN or ACKNOWLEDGED. Resolving an already-RESOLVED
     * incident throws {@link IllegalStateException} (mapped to 409). The {@code resolvedBy}
     * audits whether the close was a manual operator action or {@link #SYSTEM_RESOLVER}.
     */
    public void resolve(String resolvedBy) {
        if (this.status == Status.RESOLVED) {
            throw new IllegalStateException(
                    "Incident " + id + " is already resolved");
        }
        this.status = Status.RESOLVED;
        this.resolvedAt = Instant.now();
        this.resolvedBy = resolvedBy;
        this.updatedAt = Instant.now();
    }
}
