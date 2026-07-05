package uz.orientadvertise.services.api.controller;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.service.EventQueryService;

/**
 * Filtered event lookup. Validation rules and edge cases are enforced in
 * {@link EventQueryService} (which throws {@code IllegalArgumentException} → 400 via the
 * global handler):
 *
 * <ul>
 *   <li>At least one of {@code deviceId} / {@code facilityId} must be set</li>
 *   <li>Date range capped at 90 days; missing bounds default to "last 90 days ending now"</li>
 *   <li>Page size ≤ 100</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventQueryService eventQueryService;

    public EventController(EventQueryService eventQueryService) {
        this.eventQueryService = eventQueryService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<Page<EventDto>> list(
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) Long facilityId,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Event.Priority priority,
            Pageable pageable) {
        Page<Event> page = eventQueryService.findFiltered(deviceId, facilityId, priority,
                from, to, pageable);
        return ResponseEntity.ok(page.map(EventDto::from));
    }

    public record EventDto(Long id, Long deviceId, String eventType, String priority,
                            String payload, Instant occurredAt, Instant createdAt) {
        public static EventDto from(Event e) {
            return new EventDto(
                    e.getId(),
                    e.getDevice().getId(),
                    e.getEventType(),
                    e.getPriority().name(),
                    e.getPayload(),
                    e.getOccurredAt(),
                    e.getCreatedAt());
        }
    }
}
