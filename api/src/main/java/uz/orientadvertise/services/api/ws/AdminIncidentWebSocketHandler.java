package uz.orientadvertise.services.api.ws;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Holds all open Admin/Operator incident sessions and fans out batched payloads.
 *
 * <p>Authentication is enforced upstream by Spring Security on the {@code /ws/admin/**}
 * path matcher (requires ADMIN or OPERATOR role). By the time {@code afterConnectionEstablished}
 * fires, the session has an authenticated principal.
 */
@Component
public class AdminIncidentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminIncidentWebSocketHandler.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        var principal = session.getPrincipal();
        if (principal == null) {
            // Defense-in-depth: security config should have rejected before this point.
            log.warn("Admin WS session without principal — closing");
            try { session.close(CloseStatus.POLICY_VIOLATION); } catch (IOException ignored) {}
            return;
        }
        sessions.put(session.getId(), session);
        log.info("Admin WS connected [user={}, sessionId={}, total={}]",
                principal.getName(), session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("Admin WS disconnected [sessionId={}, status={}, total={}]",
                session.getId(), status, sessions.size());
    }

    /**
     * Send a single text frame to every connected admin/operator session. Per-session send
     * failures are logged and skipped — one bad socket must not block the rest.
     */
    public BroadcastResult broadcast(String message) {
        var text = new TextMessage(message);
        int sent = 0;
        int failed = 0;
        for (var session : sessions.values()) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(text);
                }
                sent++;
            } catch (IOException e) {
                failed++;
                log.warn("Admin WS send failed [sessionId={}]: {}", session.getId(), e.getMessage());
            }
        }
        return new BroadcastResult(sent, failed);
    }

    /**
     * Fan out the batched {@code CRITICAL_INCIDENTS} envelope, filtered <b>per item per session</b>.
     * Each {@link ScopedItem} carries its (already {@code _projectId}-stripped) JSON plus the device's
     * project. For every session we keep only the items whose project the session may see — ADMIN
     * (no captured project set) sees all; an OPERATOR sees only items in its set. A session with no
     * surviving items is skipped entirely (no empty envelope).
     */
    public BroadcastResult broadcastScopedItems(List<ScopedItem> items) {
        int sent = 0;
        int failed = 0;
        for (var session : sessions.values()) {
            if (!session.isOpen()) {
                continue;
            }
            Set<Long> projects = sessionProjects(session);   // null = ADMIN / unrestricted
            StringBuilder sb = new StringBuilder("{\"type\":\"CRITICAL_INCIDENTS\",\"items\":[");
            boolean any = false;
            for (ScopedItem it : items) {
                boolean allow = (projects == null)
                        || (it.projectId() != null && projects.contains(it.projectId()));
                if (!allow) {
                    continue;
                }
                if (any) {
                    sb.append(',');
                }
                sb.append(it.json());
                any = true;
            }
            if (!any) {
                continue;   // nothing visible for this session — skip
            }
            sb.append("]}");
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(sb.toString()));
                }
                sent++;
            } catch (IOException e) {
                failed++;
                log.warn("Admin WS scoped send failed [sessionId={}]: {}", session.getId(), e.getMessage());
            }
        }
        return new BroadcastResult(sent, failed);
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> sessionProjects(WebSocketSession session) {
        Object attr = session.getAttributes().get(AdminIncidentHandshakeInterceptor.ATTR_PROJECT_IDS);
        return (attr instanceof Set) ? (Set<Long>) attr : null;
    }

    public int connectedCount() {
        return sessions.size();
    }

    public record BroadcastResult(int sent, int failed) {}

    /** One batched critical-incident item: its device project (routing) + the wire JSON (already stripped of {@code _projectId}). */
    public record ScopedItem(Long projectId, String json) {}
}
