package uz.orientadvertise.services.domain.event;

/**
 * Domain port for inbound dashboard event JSON payloads. The infra-side Redis subscriber
 * forwards each message here; the service-side WebSocket handler implements this and pushes
 * to all connected admin/operator sessions on {@code /ws/dashboard}.
 *
 * <p>Distinct from {@link IncidentPushChannel} (which feeds the legacy
 * {@code /ws/admin/incidents} batched feed) so the two endpoints can evolve
 * independently — different payload shapes, different fan-out strategies.
 */
public interface DashboardPushChannel {

    /**
     * Push a single pre-serialized JSON message verbatim to every connected dashboard
     * session. Implementations MUST swallow individual session-write failures so one
     * dead client doesn't take down the whole fan-out.
     */
    void push(String json);
}
