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
     * Expand this schedule into concrete time windows up to a horizon.
     * For NONE, returns the single window. For repeating, generates occurrences.
     */
    public List<TimeWindow> expandOccurrences(Instant horizon) {
        var windows = new ArrayList<TimeWindow>();
        var duration = Duration.between(startTimeUtc, endTimeUtc);
        var limit = repeatEndUtc != null && repeatEndUtc.isBefore(horizon) ? repeatEndUtc : horizon;

        if (repeatType == RepeatType.NONE) {
            windows.add(new TimeWindow(startTimeUtc, endTimeUtc));
            return windows;
        }

        var currentStart = startTimeUtc;
        while (currentStart.isBefore(limit)) {
            var currentEnd = currentStart.plus(duration);
            windows.add(new TimeWindow(currentStart, currentEnd));
            currentStart = advanceByRepeatType(currentStart);
        }

        return windows;
    }

    private Instant advanceByRepeatType(Instant from) {
        var dateTime = from.atZone(ZoneOffset.UTC);
        return switch (repeatType) {
            case DAILY -> dateTime.plusDays(1).toInstant();
            case WEEKLY -> dateTime.plusWeeks(1).toInstant();
            case MONTHLY -> dateTime.plusMonths(1).toInstant();
            case NONE -> Instant.MAX;
        };
    }

    public record TimeWindow(Instant start, Instant end) {
        public boolean overlaps(TimeWindow other) {
            return start.isBefore(other.end) && end.isAfter(other.start);
        }
    }
}
