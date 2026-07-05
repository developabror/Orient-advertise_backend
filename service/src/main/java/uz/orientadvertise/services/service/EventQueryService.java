package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

/**
 * Filtered, paginated event lookup for the admin/operator UI.
 *
 * <p>Validation:
 * <ul>
 *   <li>At least one of {@code deviceId} or {@code facilityId} is required — broad
 *       "all events" scans are disallowed because the {@code event} table grows fast
 *       and an unfiltered query would scan the whole world.</li>
 *   <li>{@code (to - from)} must not exceed {@value #MAX_RANGE_DAYS} days. Missing
 *       bounds are defaulted: omitted {@code to} = now; omitted {@code from} = {@code to}
 *       minus the max range.</li>
 *   <li>Page size is capped at {@value #MAX_PAGE_SIZE} — anything larger returns 400 so
 *       the caller can't accidentally pull a giant payload.</li>
 * </ul>
 */
@Service
public class EventQueryService {

    public static final int MAX_RANGE_DAYS = 90;
    public static final int MAX_PAGE_SIZE = 100;

    private final EventRepository eventRepository;
    private final OperatorScopeResolver operatorScopeResolver;

    public EventQueryService(EventRepository eventRepository,
                             OperatorScopeResolver operatorScopeResolver) {
        this.eventRepository = eventRepository;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    @Transactional(readOnly = true)
    public Page<Event> findFiltered(Long deviceId, Long facilityId, Event.Priority priority,
                                     Instant from, Instant to, Pageable pageable) {
        if (deviceId == null && facilityId == null) {
            throw new IllegalArgumentException(
                    "At least one of deviceId or facilityId is required");
        }
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
                : resolvedTo.minus(Duration.ofDays(MAX_RANGE_DAYS));

        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(resolvedFrom, resolvedTo).toDays() > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "Date range cannot exceed " + MAX_RANGE_DAYS + " days");
        }

        // An out-of-scope supplied device/facility simply yields an empty page (the
        // projectIds filter excludes it) — no explicit 404, per the events contract.
        return eventRepository.findFiltered(deviceId, facilityId, priority,
                resolvedFrom, resolvedTo, scope.narrowingIds(), pageable);
    }
}
