package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.model.Incident;
import uz.orientadvertise.services.service.IncidentService;
import uz.orientadvertise.services.service.OperatorScopeResolver;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

/**
 * Admin/Operator-only HTTP view of open incidents.
 *
 * <p>Edge case: user offline at incident time — sees it on next page load. The admin UI
 * fetches {@code GET /service/incidents/open} on page mount to backfill any incidents that
 * fired while their browser was closed; live updates after that arrive via the
 * {@code /ws/admin/incidents} WebSocket.
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService incidentService;
    private final OperatorScopeResolver operatorScopeResolver;

    public IncidentController(IncidentService incidentService,
                             OperatorScopeResolver operatorScopeResolver) {
        this.incidentService = incidentService;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    @GetMapping("/open")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<List<IncidentDto>> getOpen(
            @RequestParam(required = false) Event.Priority priority) {
        ScopedProjects scope = operatorScopeResolver.resolve();
        var open = incidentService.getOpenScoped(scope.restricted() ? scope.projectIds() : null);
        var filtered = priority == null
                ? open
                : open.stream().filter(i -> i.getPriority() == priority).toList();
        return ResponseEntity.ok(filtered.stream().map(IncidentDto::from).toList());
    }

    /**
     * Move an incident to ACKNOWLEDGED. Allowed only from OPEN — re-acknowledging or
     * acknowledging a resolved incident returns 409 (via {@code IllegalStateException}
     * mapped by GlobalExceptionHandler).
     */
    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<IncidentDto> acknowledge(@PathVariable Long id) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var by = auth != null ? auth.getName() : "unknown";
        var incident = incidentService.acknowledge(id, by);
        return ResponseEntity.ok(IncidentDto.from(incident));
    }

    /**
     * Move an incident to RESOLVED. Allowed from OPEN or ACKNOWLEDGED.
     * Edge case: resolving an already-resolved incident returns 409 — protects manual
     * close audit trails and prevents duplicate close events.
     */
    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<IncidentDto> resolve(@PathVariable Long id) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var by = auth != null ? auth.getName() : "unknown";
        var incident = incidentService.resolve(id, by);
        return ResponseEntity.ok(IncidentDto.from(incident));
    }

    public record IncidentDto(Long id, Long deviceId, String eventType, String status,
                               String priority, String description, int occurrenceCount,
                               Instant openedAt, Instant updatedAt,
                               Instant acknowledgedAt, String acknowledgedBy,
                               Instant resolvedAt, String resolvedBy) {
        public static IncidentDto from(Incident i) {
            return new IncidentDto(
                    i.getId(),
                    i.getDevice().getId(),
                    i.getEventType(),
                    i.getStatus().name(),
                    i.getPriority().name(),
                    i.getDescription(),
                    i.getOccurrenceCount(),
                    i.getOpenedAt(),
                    i.getUpdatedAt(),
                    i.getAcknowledgedAt(),
                    i.getAcknowledgedBy(),
                    i.getResolvedAt(),
                    i.getResolvedBy());
        }
    }
}
