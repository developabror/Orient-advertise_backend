package uz.orientadvertise.services.service;

import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.event.CriticalIncidentBroadcaster;
import uz.orientadvertise.services.domain.event.CriticalIncidentBroadcaster.IncidentSummary;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.IncidentPayload;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.model.Incident;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.domain.repository.IncidentRepository;

@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository incidentRepository;
    private final EventRepository eventRepository;
    private final CriticalIncidentBroadcaster criticalBroadcaster;
    private final DashboardEventBroadcaster dashboardBroadcaster;
    private final OperatorScopeResolver operatorScopeResolver;

    public IncidentService(IncidentRepository incidentRepository, EventRepository eventRepository,
                            CriticalIncidentBroadcaster criticalBroadcaster,
                            DashboardEventBroadcaster dashboardBroadcaster,
                            OperatorScopeResolver operatorScopeResolver) {
        this.incidentRepository = incidentRepository;
        this.eventRepository = eventRepository;
        this.criticalBroadcaster = criticalBroadcaster;
        this.dashboardBroadcaster = dashboardBroadcaster;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    /**
     * Process an event and create or update an incident.
     * Edge case: one open incident per device per event type — no duplicates.
     * If an open incident already exists for this device + event type, update it
     * (increment occurrence count, update last_event, escalate priority if higher).
     * Otherwise, create a new incident.
     */
    @Transactional
    public IncidentResult processEvent(Event event) {
        var saved = eventRepository.save(event);

        var existing = incidentRepository.findOpenByDeviceAndEventType(
                event.getDevice().getId(), event.getEventType());

        if (existing.isPresent()) {
            var incident = existing.get();
            var priorityBefore = incident.getPriority();
            incident.recordRepeatOccurrence(saved);
            log.info("Updated existing incident [id={}, device={}, type={}, count={}]",
                    incident.getId(), event.getDevice().getId(),
                    event.getEventType(), incident.getOccurrenceCount());
            // Only broadcast on a fresh CRITICAL escalation — not on every repeat that's
            // already CRITICAL. Operators have already been alerted; further occurrences
            // appear in their open-incidents list on next refresh.
            if (incident.getPriority() == Event.Priority.CRITICAL && priorityBefore != Event.Priority.CRITICAL) {
                broadcastCritical(incident);
            }
            return new IncidentResult(incident, false);
        }

        var incident = new Incident(
                event.getDevice(),
                event.getEventType(),
                event.getPriority(),
                "Auto-created from event: " + event.getEventType(),
                saved
        );
        incidentRepository.save(incident);
        log.info("Created new incident [device={}, type={}, priority={}]",
                event.getDevice().getId(), event.getEventType(), event.getPriority());
        if (incident.getPriority() == Event.Priority.CRITICAL) {
            broadcastCritical(incident);
        }
        return new IncidentResult(incident, true);
    }

    private void broadcastCritical(Incident incident) {
        try {
            criticalBroadcaster.broadcast(new IncidentSummary(
                    incident.getId(),
                    incident.getDevice().getId(),
                    incident.getEventType(),
                    incident.getPriority().name(),
                    incident.getDescription(),
                    incident.getOccurrenceCount(),
                    incident.getOpenedAt(),
                    incident.getUpdatedAt(),
                    projectIdOf(incident)));
        } catch (Exception e) {
            // Broadcast is best-effort — admin still sees the incident on next page load.
            log.warn("Critical incident broadcast failed [id={}]: {}", incident.getId(), e.getMessage());
        }
        // Dashboard live feed (FE-05/14/29) — separate channel from the legacy
        // /ws/admin/incidents fan-out, with a tagged payload the dashboard dispatches on.
        safeDashboard(() -> dashboardBroadcaster.incidentCritical(toPayload(incident, null)));
    }

    private void broadcastUpdated(Incident incident, String actor) {
        safeDashboard(() -> dashboardBroadcaster.incidentUpdated(toPayload(incident, actor)));
    }

    private static IncidentPayload toPayload(Incident incident, String actor) {
        return new IncidentPayload(
                incident.getId(),
                incident.getDevice().getId(),
                incident.getEventType(),
                incident.getStatus().name(),
                incident.getPriority().name(),
                incident.getDescription(),
                incident.getOpenedAt(),
                incident.getUpdatedAt(),
                actor,
                projectIdOf(incident));
    }

    /** The device's project — server-internal WS routing key. Null-safe (region is non-null in practice). */
    private static Long projectIdOf(Incident incident) {
        var device = incident.getDevice();
        if (device == null || device.getRegion() == null || device.getRegion().getProject() == null) {
            return null;
        }
        return device.getRegion().getProject().getId();
    }

    private void safeDashboard(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("Dashboard broadcast failed: {}", e.getMessage());
        }
    }

    @Transactional
    public Incident acknowledge(Long incidentId) {
        return acknowledge(incidentId, null);
    }

    @Transactional
    public Incident acknowledge(Long incidentId, String acknowledgedBy) {
        var incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident", incidentId));
        assertIncidentInScope(incident);
        incident.acknowledge(acknowledgedBy);
        broadcastUpdated(incident, acknowledgedBy);
        return incident;
    }

    @Transactional
    public Incident resolve(Long incidentId) {
        return resolve(incidentId, null);
    }

    @Transactional
    public Incident resolve(Long incidentId, String resolvedBy) {
        var incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident", incidentId));
        assertIncidentInScope(incident);
        incident.resolve(resolvedBy);
        broadcastUpdated(incident, resolvedBy);
        return incident;
    }

    /** Operator scope guard for HTTP ack/resolve — out-of-scope incident ⇒ 404. */
    private void assertIncidentInScope(Incident incident) {
        if (operatorScopeResolver.resolve()
                .excludes(incident.getDevice().getRegion().getProject().getId())) {
            throw new ResourceNotFoundException("Incident", incident.getId());
        }
    }

    /**
     * Auto-close an open incident for {@code (deviceId, eventType)} when the underlying
     * condition has cleared (heartbeat returned, version match restored, etc).
     *
     * <p>Edge case: must not override a manually-resolved incident. The method is a no-op
     * if no open incident is found OR if the incident is already in {@link Incident.Status#RESOLVED}
     * — manual resolutions stay sealed. Returns {@code true} only when this call actually
     * transitioned the incident.
     */
    @Transactional
    public boolean autoResolveOnRecovery(Long deviceId, String eventType) {
        var existing = incidentRepository.findOpenByDeviceAndEventType(deviceId, eventType);
        if (existing.isEmpty()) {
            return false;
        }
        var incident = existing.get();
        if (incident.isResolved()) {
            // Race: someone manually resolved between our query and decision.
            return false;
        }
        incident.resolve(Incident.SYSTEM_RESOLVER);
        log.info("Auto-resolved incident on recovery [id={}, device={}, type={}]",
                incident.getId(), deviceId, eventType);
        broadcastUpdated(incident, Incident.SYSTEM_RESOLVER);
        return true;
    }

    @Transactional(readOnly = true)
    public List<Incident> getByDevice(Long deviceId) {
        return incidentRepository.findByDeviceIdOrderByUpdatedAtDesc(deviceId);
    }

    @Transactional(readOnly = true)
    public List<Incident> getOpen() {
        return incidentRepository.findByStatus(Incident.Status.OPEN);
    }

    /**
     * Open incidents narrowed to an explicit project set. Context-free — the caller (HTTP
     * controller via {@link OperatorScopeResolver}, or the WS snapshot via the session's
     * captured scope) supplies {@code projectIds}; this method NEVER reads the SecurityContext.
     * {@code null} ⇒ unrestricted; empty ⇒ {@code List.of()} (no empty-IN); non-empty ⇒ scoped.
     */
    @Transactional(readOnly = true)
    public List<Incident> getOpenScoped(Collection<Long> projectIds) {
        if (projectIds == null) {
            return incidentRepository.findByStatus(Incident.Status.OPEN);
        }
        if (projectIds.isEmpty()) {
            return List.of();
        }
        return incidentRepository.findOpenByProjectIds(projectIds);
    }

    public record IncidentResult(Incident incident, boolean created) {
        public boolean wasUpdated() { return !created; }
    }
}
