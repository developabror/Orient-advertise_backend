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
import java.util.Comparator;

@Entity
@Table(name = "content_assignment")
public class ContentAssignment {

    /**
     * The device→playlist resolution order, as a Java comparator: <b>most specific target wins;
     * on a tie the most recently CONFIRMED wins; on a further tie the higher id wins.</b> The
     * greatest element under this comparator is the winner, so callers use {@code .max(PRECEDENCE)}.
     *
     * <p>Recency is what makes "Replace" override only its own window (v1.0.142). A new campaign
     * and the long-running assignment it replaces are BOTH left CONFIRMED and overlapping; the new
     * one wins while it runs, and the predecessor resumes the moment it ends. Ordering on
     * {@code confirmedAt} (not {@code updatedAt}) is deliberate: {@link #bumpVersion()} and
     * {@link #truncateEndTo(Instant)} move {@code updatedAt}, so an unrelated edit would otherwise
     * steal precedence.
     *
     * <p><b>This is one of FOUR copies of the same total order</b> and they must agree exactly:
     * this comparator (used by {@code ContentAssignmentService.resolveForDevice} and
     * {@code previewForTarget}), the JPQL {@code ContentAssignmentRepository.findActiveAtTime},
     * and the SQL inside {@code device_status_view} (V48). The SQL reads
     * {@code ORDER BY ca.priority DESC, COALESCE(ca.confirmed_at, ca.created_at) DESC, ca.id DESC}.
     *
     * <p>Null-safe on purpose — a not-yet-persisted entity has no id, and a DRAFT/pre-V48 row has
     * no {@code confirmedAt}. Nulls sort FIRST, i.e. they lose. <b>The SQL side never has to agree
     * about nulls</b>, because it never sees one: {@code COALESCE(confirmed_at, created_at)} falls
     * back to {@code created_at}, which is {@code NOT NULL} since V7, and {@code id} is the primary
     * key. (Do not reason from a database's NULL ordering default here — H2 and PostgreSQL differ
     * on it. The COALESCE is what makes the two sides equal.) Because every tie is broken down to
     * the id, the order is TOTAL: two distinct persisted rows can never compare equal, so Java and
     * SQL cannot silently diverge.
     */
    public static final Comparator<ContentAssignment> PRECEDENCE =
            Comparator.comparingInt(ContentAssignment::getPriority)
                    .thenComparing(ContentAssignment::effectiveConfirmedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(ContentAssignment::getId,
                            Comparator.nullsFirst(Comparator.naturalOrder()));

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

    /**
     * When this assignment went CONFIRMED — the recency term of {@link #PRECEDENCE}. Null for a row
     * that was never confirmed (DRAFT / CANCELLED-from-draft) and for rows written before V48,
     * whose backfill set it to {@code createdAt}; readers fall back to {@code createdAt} either way.
     */
    @Column
    private Instant confirmedAt;

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
        this.confirmedAt = Instant.now();
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
    public Instant getConfirmedAt() { return confirmedAt; }

    /**
     * The recency instant {@link #PRECEDENCE} orders on: {@code confirmedAt} when known, else
     * {@code createdAt}. The exact Java mirror of the SQL views'
     * {@code COALESCE(ca.confirmed_at, ca.created_at)}. May be null only for a mock/partially
     * built entity; the comparator sorts that last.
     */
    public Instant effectiveConfirmedAt() {
        return confirmedAt != null ? confirmedAt : createdAt;
    }
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
