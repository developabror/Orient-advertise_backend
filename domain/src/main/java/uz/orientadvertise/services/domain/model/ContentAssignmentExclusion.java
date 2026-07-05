package uz.orientadvertise.services.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "content_assignment_exclusion", uniqueConstraints = {
        @UniqueConstraint(name = "uq_exclusion_per_device", columnNames = {"assignment_id", "device_id"})
})
public class ContentAssignmentExclusion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id", nullable = false)
    private ContentAssignment assignment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    protected ContentAssignmentExclusion() {
    }

    public ContentAssignmentExclusion(ContentAssignment assignment, Device device, String reason) {
        this.assignment = assignment;
        this.device = device;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public ContentAssignment getAssignment() { return assignment; }
    public Device getDevice() { return device; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
