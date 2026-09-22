package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.InvalidUploadException;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.content.ScheduleEvaluator;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.Schedule;
import uz.orientadvertise.services.domain.model.Schedule.RepeatType;
import uz.orientadvertise.services.domain.model.Schedule.TimeWindow;
import uz.orientadvertise.services.domain.repository.ScheduleRepository;

@Service
public class ScheduleService implements ScheduleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final ScheduleRepository scheduleRepository;
    private final OperatorScopeResolver operatorScopeResolver;

    public ScheduleService(ScheduleRepository scheduleRepository,
                           OperatorScopeResolver operatorScopeResolver) {
        this.scheduleRepository = scheduleRepository;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    private void assertAssignmentInScope(ContentAssignment assignment) {
        if (operatorScopeResolver.resolve()
                .excludes(assignment.getPlaylist().getProject().getId())) {
            throw new ResourceNotFoundException("ContentAssignment", assignment.getId());
        }
    }

    /**
     * Create a schedule for an assignment. Overlapping schedules raise a warning
     * but do NOT block creation — this is by design (soft conflict, not hard block).
     *
     * @return result containing the saved schedule and any overlap warnings
     */
    @Transactional
    public ScheduleResult createSchedule(ContentAssignment assignment, Instant startTimeUtc,
                                          Instant endTimeUtc, RepeatType repeatType,
                                          Instant repeatEndUtc) {
        assertAssignmentInScope(assignment);
        validateWindow(startTimeUtc, endTimeUtc, repeatType, repeatEndUtc);

        var schedule = new Schedule(assignment, startTimeUtc, endTimeUtc, repeatType, repeatEndUtc);
        var warnings = detectOverlaps(assignment.getId(), schedule);

        if (!warnings.isEmpty()) {
            log.warn("Schedule for assignment {} has {} overlap warning(s): {}",
                    assignment.getId(), warnings.size(), warnings);
        }

        var saved = scheduleRepository.save(schedule);
        return new ScheduleResult(saved, warnings);
    }

    @Transactional(readOnly = true)
    public List<Schedule> getSchedulesForAssignment(Long assignmentId) {
        return scheduleRepository.findByAssignmentIdAndDeletedAtIsNull(assignmentId);
    }

    @Transactional
    public void softDelete(Long scheduleId) {
        var schedule = scheduleRepository.findByIdAndDeletedAtIsNull(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule", scheduleId));
        assertAssignmentInScope(schedule.getAssignment());
        schedule.softDelete();
    }

    /**
     * Replace the time window of an existing schedule. Validation parity with create
     * ({@link #validateWindow}).
     */
    @Transactional
    public Schedule update(Long scheduleId, Instant startTimeUtc, Instant endTimeUtc,
                            RepeatType repeatType, Instant repeatEndUtc) {
        var existing = scheduleRepository.findByIdAndDeletedAtIsNull(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule", scheduleId));
        assertAssignmentInScope(existing.getAssignment());

        validateWindow(startTimeUtc, endTimeUtc, repeatType, repeatEndUtc);

        // Schedule has no setters for the time fields; rather than introduce mutators
        // that complicate invariants, soft-delete the old row and create a fresh one
        // for the same assignment. PUT semantics — same logical entity, fresh state.
        existing.softDelete();
        var replacement = new Schedule(existing.getAssignment(), startTimeUtc, endTimeUtc,
                repeatType, repeatEndUtc);
        return scheduleRepository.save(replacement);
    }

    /**
     * LOGIC-12: nothing used to check the window's shape, and expansion walked every occurrence
     * from the start, so a DAILY schedule from year 1 was ~740k windows on every create and every
     * evaluation — an operator-triggered CPU/heap exhaustion. Expansion is now arithmetic and capped
     * ({@link Schedule#expandOccurrences(Instant, Instant)}); this rejects the shapes that were
     * never meaningful:
     * <ul>
     *   <li>an end at or before the start (a zero or negative window);</li>
     *   <li>a repeating window longer than its repeat interval, which overlaps its own next
     *       occurrence (MONTHLY uses 28 days, the shortest month);</li>
     *   <li>a repeat end at or before the start (no occurrence at all);</li>
     *   <li>a window, or repeat horizon, entirely in the past.</li>
     * </ul>
     */
    private static void validateWindow(Instant startTimeUtc, Instant endTimeUtc,
                                       RepeatType repeatType, Instant repeatEndUtc) {
        if (startTimeUtc == null || endTimeUtc == null) {
            throw new InvalidUploadException("Schedule start and end times are required");
        }
        if (!endTimeUtc.isAfter(startTimeUtc)) {
            throw new InvalidUploadException("Schedule end time must be after its start time");
        }
        if (repeatType != null && repeatType != RepeatType.NONE) {
            Duration interval = switch (repeatType) {
                case DAILY -> Duration.ofDays(1);
                case WEEKLY -> Duration.ofDays(7);
                case MONTHLY -> Duration.ofDays(28);
                case NONE -> throw new IllegalStateException("unreachable");
            };
            if (Duration.between(startTimeUtc, endTimeUtc).compareTo(interval) > 0) {
                throw new InvalidUploadException("A " + repeatType + " schedule's window can be at most "
                        + interval.toDays() + " day(s) long; a longer one overlaps its own next occurrence");
            }
            if (repeatEndUtc != null && !repeatEndUtc.isAfter(startTimeUtc)) {
                throw new InvalidUploadException("Schedule repeat end must be after its start time");
            }
        }
        var now = Instant.now();
        // For non-repeating: end must not be in the past.
        if (repeatType == null || repeatType == RepeatType.NONE) {
            if (!endTimeUtc.isAfter(now)) {
                throw new InvalidUploadException(
                        "Schedule end time must be in the future, got: " + endTimeUtc);
            }
            return;
        }
        // For repeating: the repeat horizon (repeatEndUtc) must not be in the past.
        if (repeatEndUtc != null && !repeatEndUtc.isAfter(now)) {
            throw new InvalidUploadException(
                    "Schedule repeat end must be in the future, got: " + repeatEndUtc);
        }
    }

    @Transactional(readOnly = true)
    public boolean isActiveAt(Schedule schedule, Instant atTimeUtc) {
        if (schedule.isDeleted()) {
            return false;
        }

        // From atTimeUtc: only the occurrences still running then, not the schedule's whole history.
        return schedule.expandOccurrences(atTimeUtc, atTimeUtc.plusSeconds(1)).stream()
                .anyMatch(w -> !atTimeUtc.isBefore(w.start()) && atTimeUtc.isBefore(w.end()));
    }

    private List<OverlapWarning> detectOverlaps(Long assignmentId, Schedule newSchedule) {
        var warnings = new ArrayList<OverlapWarning>();
        var existing = scheduleRepository.findByAssignmentIdAndDeletedAtIsNull(assignmentId);

        // Compare from now (or the new schedule's start, if later): windows already over can't
        // conflict, and a repeating schedule may start in the past — expanding it from its start
        // would spend the whole MAX_OCCURRENCES budget on history and miss every future overlap.
        var now = Instant.now();
        var from = newSchedule.getStartTimeUtc().isAfter(now) ? newSchedule.getStartTimeUtc() : now;
        var firstEnd = newSchedule.getEndTimeUtc();
        var horizon = newSchedule.getRepeatEndUtc() != null
                ? newSchedule.getRepeatEndUtc()
                : (firstEnd.isAfter(from) ? firstEnd : from).plus(Duration.ofDays(90)); // 90 days past what's left

        var newWindows = newSchedule.expandOccurrences(from, horizon);

        for (var existingSchedule : existing) {
            var existingWindows = existingSchedule.expandOccurrences(from, horizon);
            var overlap = firstOverlap(newWindows, existingWindows);
            if (overlap != null) {
                // One overlap per existing schedule is enough for the warning.
                warnings.add(new OverlapWarning(existingSchedule.getId(), overlap[0], overlap[1]));
            }
        }

        return warnings;
    }

    /**
     * The first overlapping pair, or {@code null}. Both lists come from
     * {@link Schedule#expandOccurrences(Instant, Instant)}: sorted by start and, with one fixed length
     * per schedule, by end too — so a merge walk is enough, O(n + m) instead of every pair.
     */
    private static TimeWindow[] firstOverlap(List<TimeWindow> a, List<TimeWindow> b) {
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            var x = a.get(i);
            var y = b.get(j);
            if (x.overlaps(y)) {
                return new TimeWindow[] {x, y};
            }
            if (!x.end().isAfter(y.start())) {
                i++;        // x is over before y starts
            } else {
                j++;        // y is over before x starts
            }
        }
        return null;
    }

    public record ScheduleResult(Schedule schedule, List<OverlapWarning> warnings) {
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }

    public record OverlapWarning(Long existingScheduleId, TimeWindow newWindow, TimeWindow existingWindow) {}

    /**
     * Sweep every non-deleted schedule, decide which are currently active, and
     * apply side-effects (logged here; production would push state to devices).
     * Designed for both the per-minute Quartz job and the post-downtime catch-up.
     */
    @Override
    @Transactional(readOnly = true)
    public EvaluationResult evaluateNow() {
        var now = Instant.now();
        var schedules = scheduleRepository.findAll().stream()
                .filter(s -> !s.isDeleted())
                .toList();

        int activeCount = 0;
        int errors = 0;
        for (var schedule : schedules) {
            try {
                if (isActiveAt(schedule, now)) {
                    activeCount++;
                    log.debug("Schedule {} active at {} (assignment {})",
                            schedule.getId(), now,
                            schedule.getAssignment() != null ? schedule.getAssignment().getId() : null);
                }
            } catch (Exception e) {
                errors++;
                log.warn("Failed to evaluate schedule {}: {}", schedule.getId(), e.getMessage());
            }
        }
        log.info("Schedule evaluation complete [total={}, active={}, errors={}]",
                schedules.size(), activeCount, errors);
        return new EvaluationResult(schedules.size(), activeCount, errors);
    }
}
