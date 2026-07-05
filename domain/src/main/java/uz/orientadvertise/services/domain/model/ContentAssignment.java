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
@Table(name = "content_assignment")
public class ContentAssignment {

    public enum TargetType {
        REGION(1),
        FACILITY(2),
        DEVICE_GROUP(3);

        private final int priority;

        TargetType(int priority) {
            this.priority = priority;
        }

        public int getPriority() {
            return priority;
        }
    }

    public enum Status { DRAFT, CONFIRMED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "playlist_id", nullable = false)
    private Playlist playlist;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TargetType targetType;

    @Column(nullable = false)
    private Long targetId;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private Instant startTime;

    @Column(nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant deletedAt;

    protected ContentAssignment() {
    }

    public ContentAssignment(Playlist playlist, TargetType targetType, Long targetId,
                             Instant startTime, Instant endTime) {
        this.playlist = playlist;
        this.targetType = targetType;
        this.targetId = targetId;
        this.priority = targetType.getPriority();
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = Status.CONFIRMED;
        this.versionNumber = 1;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public ContentAssignment(Playlist playlist, TargetType targetType, Long targetId,
                             Instant startTime, Instant endTime, Status status) {
        this(playlist, targetType, targetId, startTime, endTime);
        this.status = status;
    }

    public Long getId() { return id; }
    public Playlist getPlaylist() { return playlist; }
    public TargetType getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public int getPriority() { return priority; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public Status getStatus() { return status; }
    public boolean isDraft() { return status == Status.DRAFT; }
    public boolean isConfirmed() { return status == Status.CONFIRMED; }

    public int getVersionNumber() { return versionNumber; }

    /**
     * Increment the version. Call any time the playlist content effectively changes
     * for the device, including a rollback to prior content — the bump guarantees
     * a NEW version hash so devices can tell rollback apart from no-change.
     */
    public void bumpVersion() {
        this.versionNumber++;
        this.updatedAt = Instant.now();
    }

    public void confirm() {
        if (status != Status.DRAFT) {
            throw new IllegalStateException(
                    "Cannot confirm assignment in status " + status + " (must be DRAFT)");
        }
        this.status = Status.CONFIRMED;
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        this.status = Status.CANCELLED;
        this.updatedAt = Instant.now();
    }

    /**
     * Shorten this assignment's window to end at {@code newEnd} — used when a later assignment
     * supersedes a currently-running one, so history shows it ran until the cutover rather than
     * being erased. {@code newEnd} must fall after {@code startTime} to respect the DB
     * {@code CHECK (end_time > start_time)} constraint.
     */
    public void truncateEndTo(Instant newEnd) {
        if (newEnd == null || !newEnd.isAfter(startTime)) {
            throw new IllegalArgumentException("Truncated end must be after startTime");
        }
        this.endTime = newEnd;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public boolean isDeleted() { return deletedAt != null; }

    public boolean overlaps(Instant otherStart, Instant otherEnd) {
        return startTime.isBefore(otherEnd) && endTime.isAfter(otherStart);
    }

    public void softDelete() {
        this.deletedAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
