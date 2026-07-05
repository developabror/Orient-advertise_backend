package uz.orientadvertise.services.service;

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
        rejectIfWindowEntirelyInPast(startTimeUtc, endTimeUtc, repeatType, repeatEndUtc);

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
     * Replace the time window of an existing schedule. Validation parity with create:
     * the (possibly repeating) window must not be entirely in the past.
     */
    @Transactional
    public Schedule update(Long scheduleId, Instant startTimeUtc, Instant endTimeUtc,
                            RepeatType repeatType, Instant repeatEndUtc) {
        var existing = scheduleRepository.findByIdAndDeletedAtIsNull(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule", scheduleId));
        assertAssignmentInScope(existing.getAssignment());

        rejectIfWindowEntirelyInPast(startTimeUtc, endTimeUtc, repeatType, repeatEndUtc);

        // Schedule has no setters for the time fields; rather than introduce mutators
        // that complicate invariants, soft-delete the old row and create a fresh one
        // for the same assignment. PUT semantics — same logical entity, fresh state.
        existing.softDelete();
        var replacement = new Schedule(existing.getAssignment(), startTimeUtc, endTimeUtc,
                repeatType, repeatEndUtc);
        return scheduleRepository.save(replacement);
    }

    private void rejectIfWindowEntirelyInPast(Instant startTimeUtc, Instant endTimeUtc,
                                               RepeatType repeatType, Instant repeatEndUtc) {
        var now = Instant.now();
        // For non-repeating: end must not be in the past.
        if (repeatType == null || repeatType == RepeatType.NONE) {
            if (endTimeUtc != null && !endTimeUtc.isAfter(now)) {
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

        var horizon = atTimeUtc.plusSeconds(1);
        return schedule.expandOccurrences(horizon).stream()
                .anyMatch(w -> !atTimeUtc.isBefore(w.start()) && atTimeUtc.isBefore(w.end()));
    }

    private List<OverlapWarning> detectOverlaps(Long assignmentId, Schedule newSchedule) {
        var warnings = new ArrayList<OverlapWarning>();
        var existing = scheduleRepository.findByAssignmentIdAndDeletedAtIsNull(assignmentId);

        var horizon = newSchedule.getRepeatEndUtc() != null
                ? newSchedule.getRepeatEndUtc()
                : newSchedule.getEndTimeUtc().plusSeconds(86400 * 90); // 90 day horizon for repeating

        var newWindows = newSchedule.expandOccurrences(horizon);

        for (var existingSchedule : existing) {
            var existingWindows = existingSchedule.expandOccurrences(horizon);

            for (var nw : newWindows) {
                for (var ew : existingWindows) {
                    if (nw.overlaps(ew)) {
                        warnings.add(new OverlapWarning(
                                existingSchedule.getId(),
                                nw,
                                ew
                        ));
                        break; // One overlap per existing schedule is enough for the warning
                    }
                }
                if (!warnings.isEmpty() && java.util.Objects.equals(warnings.getLast().existingScheduleId(), existingSchedule.getId())) {
                    break; // Already warned about this existing schedule
                }
            }
        }

        return warnings;
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
