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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "schedule")
public class Schedule {

    public enum RepeatType { NONE, DAILY, WEEKLY, MONTHLY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id", nullable = false)
    private ContentAssignment assignment;

    @Column(nullable = false)
    private Instant startTimeUtc;

    @Column(nullable = false)
    private Instant endTimeUtc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RepeatType repeatType;

    @Column
    private Instant repeatEndUtc;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant deletedAt;

    protected Schedule() {
    }

    public Schedule(ContentAssignment assignment, Instant startTimeUtc, Instant endTimeUtc,
                    RepeatType repeatType, Instant repeatEndUtc) {
        this.assignment = assignment;
        this.startTimeUtc = startTimeUtc;
        this.endTimeUtc = endTimeUtc;
        this.repeatType = repeatType;
        this.repeatEndUtc = repeatEndUtc;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public ContentAssignment getAssignment() { return assignment; }
    public Instant getStartTimeUtc() { return startTimeUtc; }
    public Instant getEndTimeUtc() { return endTimeUtc; }
    public RepeatType getRepeatType() { return repeatType; }
    public Instant getRepeatEndUtc() { return repeatEndUtc; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public boolean isDeleted() { return deletedAt != null; }

    public void softDelete() {
        this.deletedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Hard cap on the windows one expansion returns (LOGIC-12). Inputs are validated since
     * v1.0.146, but rows saved before that were not — a DAILY schedule from year 1 to 9999 is
     * millions of windows — and a GET of such a row must not be able to exhaust the heap.
     */
    public static final int MAX_OCCURRENCES = 1_000;

    /** {@link #expandOccurrences(Instant, Instant)} from the schedule's own start. */
    public List<TimeWindow> expandOccurrences(Instant horizon) {
        return expandOccurrences(startTimeUtc, horizon);
    }

    /**
     * The occurrences that are still running or yet to come at {@code from} (end after
     * {@code from}) and start before {@code horizon} and {@code repeatEndUtc}, oldest first, at most
     * {@link #MAX_OCCURRENCES}. NONE always returns its single window.
     *
     * <p>Occurrence {@code n} is computed from the original start ({@code start + n periods}), not by
     * stepping from the previous one, so the first relevant occurrence is found without walking the
     * schedule's whole history, and MONTHLY keeps its day: Jan 31 → Feb 28 → Mar 31 (stepping
     * drifted to Mar 28 and stayed there).
     */
    public List<TimeWindow> expandOccurrences(Instant from, Instant horizon) {
        var windows = new ArrayList<TimeWindow>();
        var duration = Duration.between(startTimeUtc, endTimeUtc);

        if (repeatType == RepeatType.NONE) {
            windows.add(new TimeWindow(startTimeUtc, endTimeUtc));
            return windows;
        }

        var limit = repeatEndUtc != null && repeatEndUtc.isBefore(horizon) ? repeatEndUtc : horizon;
        for (long n = firstRelevantIndex(from.minus(duration)); windows.size() < MAX_OCCURRENCES; n++) {
            var start = occurrenceStart(n);
            if (!start.isBefore(limit)) {
                break;
            }
            var end = start.plus(duration);
            if (end.isAfter(from)) {
                windows.add(new TimeWindow(start, end));
            }
        }
        return windows;
    }

    /**
     * An index at or below the first occurrence starting after {@code endedBy} (every earlier one
     * has already ended). {@code between} truncates, so one step back keeps it a lower bound; the
     * loop above skips the at most two occurrences that are already over.
     */
    private long firstRelevantIndex(Instant endedBy) {
        if (!endedBy.isAfter(startTimeUtc)) {
            return 0;
        }
        var anchor = startTimeUtc.atZone(ZoneOffset.UTC);
        var target = endedBy.atZone(ZoneOffset.UTC);
        long n = switch (repeatType) {
            case DAILY -> ChronoUnit.DAYS.between(anchor, target);
            case WEEKLY -> ChronoUnit.WEEKS.between(anchor, target);
            case MONTHLY -> ChronoUnit.MONTHS.between(anchor, target);
            case NONE -> 0;
        };
        return Math.max(0, n - 1);
    }

    private Instant occurrenceStart(long n) {
        var anchor = startTimeUtc.atZone(ZoneOffset.UTC);
        return switch (repeatType) {
            case DAILY -> anchor.plusDays(n).toInstant();
            case WEEKLY -> anchor.plusWeeks(n).toInstant();
            case MONTHLY -> anchor.plusMonths(n).toInstant();
            case NONE -> startTimeUtc;
        };
    }

    public record TimeWindow(Instant start, Instant end) {
        public boolean overlaps(TimeWindow other) {
            return start.isBefore(other.end) && end.isAfter(other.start);
        }
    }
}
