package uz.orientadvertise.services.api.ws;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import uz.orientadvertise.services.api.controller.IncidentController;
import uz.orientadvertise.services.domain.event.DashboardPushChannel;
import uz.orientadvertise.services.service.IncidentService;

/**
 * Live dashboard feed for FE-05 / FE-14 / FE-29. Fans out three event types
 * (INCIDENT_CRITICAL, INCIDENT_UPDATED, DEVICE_STATUS_CHANGE) to every connected
 * Admin/Operator session, plus a one-time SNAPSHOT frame on connect.
 *
 * <p><b>Single responsibility — broadcast only.</b> Inbound text frames are silently
 * dropped: the dashboard issues commands via normal HTTP endpoints, never over this
 * channel. {@link #handleTextMessage} logs and discards rather than dispatching.
 *
 * <p>Authentication is enforced at the handshake by {@link DashboardHandshakeInterceptor}.
 * By the time this handler sees a session, the principal is already an Admin or
 * Operator; {@code afterConnectionEstablished} double-checks for defense-in-depth.
 *
 * <p><b>Snapshot-then-deltas ordering.</b> On every successful connect the handler
 * sends a {@link SnapshotFrame} before registering the session for live broadcasts.
 * That ordering is what lets the FE drop its open-incident HTTP backfill: the snapshot
 * carries the authoritative open-incident set at handshake time, every subsequent
 * delta is a live update of that set, and the FE de-dupes by {@code incidentId} so
 * there's no duplicate-incident risk if a delta lands while the snapshot was in
 * flight. See the README's "Dashboard Live Feed" section for the rationale.
 */
@Component
public class DashboardWebSocketHandler extends TextWebSocketHandler implements DashboardPushChannel {

    private static final Logger log = LoggerFactory.getLogger(DashboardWebSocketHandler.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final IncidentService incidentService;
    private final ObjectMapper objectMapper;

    public DashboardWebSocketHandler(IncidentService incidentService, ObjectMapper objectMapper) {
        this.incidentService = incidentService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Object username = session.getAttributes().get(DashboardHandshakeInterceptor.ATTR_USERNAME);
        if (username == null) {
            log.warn("Dashboard WS session without authenticated principal — closing");
            try { session.close(CloseStatus.POLICY_VIOLATION); } catch (IOException ignored) {}
            return;
        }

        // Snapshot fires BEFORE session registration so the client receives the
        // authoritative open-incident set first. If the snapshot fails for any reason
        // (IncidentService throws, send fails, session closed mid-handshake) we log a
        // warn and continue — the live channel still works, and the FE can backfill
        // via GET /api/incidents/open as a fallback. Closing the socket on snapshot
        // failure would be wrong: the operator loses live updates over a stale read.
        sendSnapshotBestEffort(session);

        sessions.put(session.getId(), session);
        log.info("Dashboard WS connected [user={}, sessionId={}, total={}]",
                username, session.getId(), sessions.size());
    }

    /**
     * Build and send the connect-time snapshot. Two layers of best-effort isolation:
     * <ol>
     *   <li>{@link IncidentService#getOpen()} throwing is caught and logged — happens
     *       e.g. when the incident table is briefly unreachable. We still register the
     *       session; the FE backfills via HTTP.</li>
     *   <li>Per-session send failure (closed mid-handshake, network error during
     *       initial flush) is caught and logged. The session still registers; live
     *       deltas may or may not arrive depending on the session state.</li>
     * </ol>
     */
    private void sendSnapshotBestEffort(WebSocketSession session) {
        if (!session.isOpen()) {
            return;
        }
        List<IncidentController.IncidentDto> open;
        try {
            // Scope the snapshot to the session's captured project set. null (ADMIN) ⇒ all open
            // incidents; empty (OPERATOR with no projects) ⇒ none; a set ⇒ only those projects.
            @SuppressWarnings("unchecked")
            Collection<Long> projectIds = (Collection<Long>) session.getAttributes()
                    .get(DashboardHandshakeInterceptor.ATTR_PROJECT_IDS);
            open = incidentService.getOpenScoped(projectIds).stream()
                    .map(IncidentController.IncidentDto::from)
                    .toList();
        } catch (Exception e) {
            log.warn("Dashboard snapshot: incidentService.getOpen() failed [sessionId={}]: {} — continuing",
                    session.getId(), e.getMessage());
            return;
        }

        String json;
        try {
            // Build the frame manually to keep the on-the-wire shape identical to the
            // README contract: type / serverTime / openIncidents in that order. Jackson
            // record serialization preserves declaration order.
            json = objectMapper.writeValueAsString(new SnapshotFrame(Instant.now(), open));
        } catch (Exception e) {
            log.warn("Dashboard snapshot: serialization failed [sessionId={}]: {} — continuing",
                    session.getId(), e.getMessage());
            return;
        }

        try {
            // Synchronize per-session: even though the session is brand-new and not yet
            // in `sessions`, a future-proof pattern matches the broadcast path.
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            log.debug("Dashboard snapshot sent [sessionId={}, openIncidents={}]",
                    session.getId(), open.size());
        } catch (IOException e) {
            log.warn("Dashboard snapshot: send failed [sessionId={}]: {} — continuing",
                    session.getId(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("Dashboard WS disconnected [sessionId={}, status={}, total={}]",
                session.getId(), status, sessions.size());
    }

    /**
     * Broadcast-only contract: any inbound text frame is logged and dropped. The dashboard
     * is meant to be a one-way fan-out — operator actions go through HTTP, never here.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("Dropped inbound dashboard WS frame [sessionId={}, length={}]",
                session.getId(), message.getPayloadLength());
    }

    /**
     * Fan out a dashboard frame. Device/incident frames arrive wrapped in the routing envelope
     * {@code {"_projectId":<num|null>,"payload":<frame>}}: we read {@code _projectId}, send ONLY
     * the inner {@code payload} (the {@code _projectId} is stripped — never on the wire), and
     * filter per session — ADMIN (no captured set) gets every frame; an OPERATOR gets it only when
     * the frame's project is in their set. Unwrapped frames (e.g. CONTENT_STATUS_CHANGE) broadcast
     * to every session unchanged.
     */
    @Override
    public void push(String json) {
        boolean scoped = false;
        Long projectId = null;
        String frame = json;
        try {
            var node = objectMapper.readTree(json);
            if (node.has("payload") && node.has("_projectId")) {
                scoped = true;
                var pid = node.get("_projectId");
                projectId = pid.isNull() ? null : pid.asLong();
                frame = objectMapper.writeValueAsString(node.get("payload"));   // strip _projectId
            }
        } catch (Exception e) {
            // Not an envelope (or malformed) — treat as a raw broadcast frame.
        }

        var text = new TextMessage(frame);
        int sent = 0;
        int failed = 0;
        for (var session : sessions.values()) {
            if (!session.isOpen()) continue;
            if (scoped && !sessionAllows(session, projectId)) continue;
            try {
                // Spring's raw WebSocketSession is not thread-safe on send — multiple
                // pub/sub threads can land here concurrently, so synchronize per-session.
                synchronized (session) {
                    session.sendMessage(text);
                }
                sent++;
            } catch (IOException e) {
                failed++;
                log.warn("Dashboard WS send failed [sessionId={}]: {}", session.getId(), e.getMessage());
            }
        }
        if (failed > 0) {
            log.debug("Dashboard WS broadcast: sent={}, failed={}", sent, failed);
        }
    }

    /** ADMIN (no captured set) sees every scoped frame; an OPERATOR only those in their project set. */
    @SuppressWarnings("unchecked")
    private static boolean sessionAllows(WebSocketSession session, Long projectId) {
        Object attr = session.getAttributes().get(DashboardHandshakeInterceptor.ATTR_PROJECT_IDS);
        if (!(attr instanceof Set)) {
            return true;   // ADMIN / unrestricted
        }
        Set<Long> projects = (Set<Long>) attr;
        return projectId != null && projects.contains(projectId);
    }

    public int connectedCount() {
        return sessions.size();
    }

    /**
     * Connect-time snapshot frame in the dashboard event JSON union. Distinct from the
     * three live event types ({@code INCIDENT_CRITICAL}, {@code INCIDENT_UPDATED},
     * {@code DEVICE_STATUS_CHANGE}) defined by {@link DashboardPushChannel}; the
     * snapshot is sent exactly once per session, immediately after a successful
     * handshake.
     *
     * <p>{@code openIncidents} mirrors the {@code GET /api/incidents/open} response
     * shape — same {@link IncidentController.IncidentDto} record, same field order.
     * That symmetry is intentional: the FE can route both responses through a single
     * "set the open-incident list" reducer.
     */
    public record SnapshotFrame(String type, Instant serverTime,
                                  List<IncidentController.IncidentDto> openIncidents) {
        public SnapshotFrame(Instant serverTime, List<IncidentController.IncidentDto> openIncidents) {
            this("SNAPSHOT", serverTime, openIncidents);
        }
    }
}
