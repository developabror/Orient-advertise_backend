package uz.orientadvertise.services.domain.event;

import java.time.Instant;

/**
 * Domain port for the dashboard live feed (FE-05 / FE-14 / FE-29). Three event kinds
 * fan out from the service layer through a Redis pub/sub bus to connected
 * Admin/Operator browser sessions on {@code /ws/dashboard}.
 *
 * <p>The interface deliberately exposes three explicit methods rather than a single
 * {@code broadcast(Object)} so the contract is type-checked at every call site.
 * Implementations serialize to JSON with a {@code type} tag the WebSocket consumer
 * can dispatch on.
 *
 * <p><b>Single responsibility.</b> This is publish-only; the {@code /ws/dashboard}
 * endpoint never accepts commands from the browser. Operator actions go through
 * normal HTTP endpoints.
 */
public interface DashboardEventBroadcaster {

    /** Fires the first time an incident transitions to CRITICAL. */
    void incidentCritical(IncidentPayload payload);

    /** Fires on every status change of an existing incident (acknowledge, resolve, auto-resolve). */
    void incidentUpdated(IncidentPayload payload);

    /** Fires when a device transitions between operational states (ONLINE/OFFLINE/NO_CONTENT). */
    void deviceStatusChanged(DeviceStatusPayload payload);

    /**
     * Fires when a content file's transcoding status changes
     * (TRANSCODING / READY / FAILED / INVALID). Lets the operator console show transcode
     * progress over the live feed instead of HTTP-polling {@code GET /api/content/{id}}.
     * Distinct from the device-facing {@code URGENT_CONTENT} push.
     *
     * <p>Routed like every other event: see {@link ContentStatusPayload} for why a content frame
     * needs <i>two</i> routing keys where a device frame needs one.
     */
    void contentStatusChanged(ContentStatusPayload payload);

    record IncidentPayload(
            Long incidentId,
            Long deviceId,
            String eventType,
            String status,
            String priority,
            String description,
            Instant openedAt,
            Instant updatedAt,
            String actor,
            // Server-internal routing only — the device's project, used to fan out to the right
            // operator sessions. NEVER written to the on-the-wire frame.
            Long projectId) {}

    record DeviceStatusPayload(
            Long deviceId,
            String oldStatus,
            String newStatus,
            Instant changedAt,
            // Server-internal routing only (see IncidentPayload.projectId).
            Long projectId) {}

    /**
     * @param invalidReason why the file was rejected (INVALID) or why the transcode failed
     *                      (FAILED); null for TRANSCODING/READY
     * @param projectId     server-internal routing only (see {@link IncidentPayload#projectId}) —
     *                      null for orphan content, which has no project to route by
     * @param uploadedBy    server-internal routing only: the uploader's username. Content
     *                      visibility is <b>owned ∪ granted</b>, NOT project-based, so routing on
     *                      {@code projectId} alone would stop an operator from seeing their own
     *                      upload transcode whenever the file is orphan or sits in a project they
     *                      are not assigned to — the exact live-feed gap this event exists to
     *                      close. Never written to the on-the-wire frame.
     */
    record ContentStatusPayload(
            Long contentId,
            String status,
            String invalidReason,
            Instant at,
            Long projectId,
            String uploadedBy) {}
}
