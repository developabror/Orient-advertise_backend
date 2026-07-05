package uz.orientadvertise.services.api.dto;

import java.time.Duration;
import java.time.Instant;

import uz.orientadvertise.services.domain.model.Schedule;

/**
 * Detail projection for {@code GET /api/schedules/{id}}. Strict superset of
 * {@link ScheduleSummary}: adds {@code nextOccurrenceUtc} so the admin UI can render
 * "next run at …" without re-deriving the recurrence rules client-side.
 *
 * <p>{@code nextOccurrenceUtc} is the start of the first occurrence at or after the
 * supplied {@code now}. The factory takes {@code now} explicitly (rather than calling
 * {@link Instant#now()} internally) so the computation is deterministic — drives both
 * production usage (the controller passes wall-clock time) and the unit test (passes a
 * fixed instant to assert mid-recurrence behavior).
 *
 * <p><b>Mid-recurrence semantics:</b> when {@code now} falls inside an occurrence's
 * {@code [start, end)} window, the <i>current</i> occurrence is considered ongoing and
 * the {@code nextOccurrenceUtc} is the start of the <b>following</b> occurrence — not
 * the start of the current one. This matches the FE expectation: a "next run at …"
 * label should advance once we're inside the current run.
 */
public record ScheduleDetail(
        Long id,
        Long assignmentId,
        Long playlistId,
        Instant startTimeUtc,
        Instant endTimeUtc,
        String repeatType,
        Instant repeatEndUtc,
        Instant createdAt,
        Instant nextOccurrenceUtc
) {
    /** Cap horizon for perpetual repeats — well past anything an operator schedules. */
    private static final Duration PERPETUAL_HORIZON = Duration.ofDays(365);

    public static ScheduleDetail from(Schedule s, Instant now) {
        var assignment = s.getAssignment();
        Long assignmentId = assignment != null ? assignment.getId() : null;
        Long playlistId = (assignment != null && assignment.getPlaylist() != null)
                ? assignment.getPlaylist().getId()
                : null;
        return new ScheduleDetail(
                s.getId(),
                assignmentId,
                playlistId,
                s.getStartTimeUtc(),
                s.getEndTimeUtc(),
                s.getRepeatType() != null ? s.getRepeatType().name() : null,
                s.getRepeatEndUtc(),
                s.getCreatedAt(),
                computeNextOccurrence(s, now));
    }

    /**
     * Reuses {@link Schedule#expandOccurrences(Instant)} — same expansion the overlap
     * detector uses, so a schedule that does not collide with any other on creation will
     * never disagree with this projection on what the next occurrence is.
     *
     * <p>The filter {@code start.isAfter(currentEnd) || start.equals(currentEnd)} is the
     * implementation of "next start, not the current one": it skips a window whose
     * {@code start <= now < end} and returns the following window's start.
     */
    private static Instant computeNextOccurrence(Schedule s, Instant now) {
        Instant horizon = pickHorizon(s, now);
        return s.expandOccurrences(horizon).stream()
                .filter(w -> {
                    boolean isCurrent = !w.start().isAfter(now) && now.isBefore(w.end());
                    boolean startsInFuture = !w.start().isBefore(now);
                    // "Next" means: a window whose start is at or after now AND we're
                    // not currently inside this same window. Mid-recurrence skips it.
                    return startsInFuture && !isCurrent;
                })
                .map(Schedule.TimeWindow::start)
                .findFirst()
                .orElse(null);
    }

    private static Instant pickHorizon(Schedule s, Instant now) {
        // For NONE the expansion ignores horizon and returns just the single window —
        // any positive horizon works.
        if (s.getRepeatType() == null || s.getRepeatType() == Schedule.RepeatType.NONE) {
            return s.getEndTimeUtc().plusSeconds(1);
        }
        // Repeating: prefer the schedule's own repeatEndUtc; fall back to a one-year cap
        // for perpetual schedules so the expansion terminates.
        if (s.getRepeatEndUtc() != null) {
            return s.getRepeatEndUtc().plusSeconds(1);
        }
        return now.plus(PERPETUAL_HORIZON);
    }
}
