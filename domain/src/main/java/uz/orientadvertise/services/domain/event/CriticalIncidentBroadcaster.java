package uz.orientadvertise.services.domain.event;

import java.time.Instant;

/**
 * Domain port for fanning out CRITICAL incidents to operator/admin clients. Implemented
 * in the infra module by a Redis pub/sub publisher; subscribed in the service module by a
 * WebSocket fan-out that pushes to connected admin/operator sessions.
 */
public interface CriticalIncidentBroadcaster {

    void broadcast(IncidentSummary summary);

    record IncidentSummary(Long incidentId, Long deviceId, String eventType, String priority,
                            String description, int occurrenceCount, Instant openedAt,
                            Instant updatedAt,
                            // Server-internal routing only — the device's project; used to fan out
                            // the batched CRITICAL_INCIDENTS feed per-item per-session. Never on the wire.
                            Long projectId) {}
}
