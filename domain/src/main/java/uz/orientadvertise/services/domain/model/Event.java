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
@Table(name = "event")
public class Event {

    @io.swagger.v3.oas.annotations.media.Schema(description = """
            Event severity. Drives incident routing and the Critical pub/sub fan-out.
            - **CRITICAL** — immediate operator attention; pushed via WebSocket
            - **HIGH** — should be triaged within minutes
            - **MEDIUM** — included in dashboards but not paged
            - **LOW** — informational, not surfaced by default
            - **INFO** — audit-only, never opens an incident
            """,
            enumAsRef = true)
    public enum Priority { CRITICAL, HIGH, MEDIUM, LOW, INFO }

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
    private Priority priority;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected Event() {
    }

    public Event(Device device, String eventType, Priority priority, String payload, Instant occurredAt) {
        this.device = device;
        this.eventType = eventType;
        this.priority = priority;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Device getDevice() { return device; }
    public String getEventType() { return eventType; }
    public Priority getPriority() { return priority; }
    public String getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
}
