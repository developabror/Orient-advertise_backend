package uz.orientadvertise.services.domain.event;

/**
 * Domain port for inbound critical-incident JSON payloads from the message bus.
 * The infra-side Redis subscriber forwards each message here; the service-side broadcaster
 * implements this and queues for the next 1-second batched flush to admin/operator
 * WebSocket sessions.
 */
public interface IncidentPushChannel {

    void enqueue(String incidentJson);
}
