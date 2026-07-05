package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.Schedule;
import uz.orientadvertise.services.domain.repository.ScheduleRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

/**
 * Filtered, paginated schedule lookup for {@code GET /api/schedules}.
 *
 * <p>Validation:
 * <ul>
 *   <li>Page size capped at {@value #MAX_PAGE_SIZE} — anything larger throws
 *       {@link IllegalArgumentException} (mapped to 400 by the global handler) so a
 *       caller cannot accidentally pull a giant payload.</li>
 *   <li>{@code (to - from)} must not exceed {@value #MAX_RANGE_DAYS} days. Missing
 *       bounds are defaulted: omitted {@code to} = now; omitted {@code from} =
 *       {@code to} minus the max range — the same trailing-90-day window the rest of
 *       the read API uses.</li>
 *   <li>{@code from > to} is rejected with 400 — silently swapping would mask an
 *       obvious caller bug.</li>
 *   <li>Soft-deleted rows are excluded at the repository layer.</li>
 * </ul>
 *
 * <p>The window filters on {@link Schedule#getStartTimeUtc()} rather than on
 * {@code endTimeUtc} or {@code createdAt}: a "schedule that starts on date X" is the
 * mental model the admin UI presents, and matches the timeline-style listing.
 */
@Service
public class ScheduleQueryService {

    public static final int MAX_RANGE_DAYS = 90;
    public static final int MAX_PAGE_SIZE = 100;
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(MAX_RANGE_DAYS);

    private final ScheduleRepository scheduleRepository;
    private final OperatorScopeResolver operatorScopeResolver;

    public ScheduleQueryService(ScheduleRepository scheduleRepository,
                                OperatorScopeResolver operatorScopeResolver) {
        this.scheduleRepository = scheduleRepository;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    @Transactional(readOnly = true)
    public Page<Schedule> findFiltered(Long assignmentId, Schedule.RepeatType repeatType,
                                         Instant from, Instant to, Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Page size cannot exceed " + MAX_PAGE_SIZE);
        }
        ScopedProjects scope = operatorScopeResolver.resolve();
        if (scope.isEmptyScope()) {
            return Page.empty(pageable);
        }

        Instant resolvedTo = to != null ? to : Instant.now();
        Instant resolvedFrom = from != null
                ? from
                : resolvedTo.minus(DEFAULT_WINDOW);

        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(resolvedFrom, resolvedTo).toDays() > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "Date range cannot exceed " + MAX_RANGE_DAYS + " days");
        }

        return scheduleRepository.findFiltered(assignmentId, repeatType,
                resolvedFrom, resolvedTo, scope.narrowingIds(), pageable);
    }

    /**
     * Single-schedule lookup for {@code GET /api/schedules/{id}}. Soft-deleted rows are
     * treated as gone (404). The repository's {@code findByIdAndDeletedAtIsNull} folds
     * the deletedAt check into the query, so missing and soft-deleted both surface as
     * the same {@link ResourceNotFoundException} — callers cannot distinguish the two.
     */
    @Transactional(readOnly = true)
    public Schedule getDetail(Long id) {
        Schedule schedule = scheduleRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule", id));
        if (operatorScopeResolver.resolve()
                .excludes(schedule.getAssignment().getPlaylist().getProject().getId())) {
            throw new ResourceNotFoundException("Schedule", id);   // out-of-scope ⇒ 404
        }
        return schedule;
    }
}
