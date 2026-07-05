package uz.orientadvertise.services.api.ws;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import uz.orientadvertise.services.service.IncidentService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardWebSocketHandlerTest {

    private DashboardWebSocketHandler handler;
    private IncidentService incidentService;

    @BeforeEach
    void setUp() {
        incidentService = mock(IncidentService.class);
        // Default: empty incident list. Each connect sends one snapshot frame; tests
        // that count broadcast sends use exact-message matchers to avoid double-counting
        // the snapshot. Tests asserting "no send" pre-set isOpen=false so the snapshot
        // skip path fires before any sendMessage call.
        when(incidentService.getOpen()).thenReturn(List.of());
        // Use the Spring-Boot-equivalent ObjectMapper config so Instant serializes as
        // ISO-8601 strings (JavaTimeModule + WRITE_DATES_AS_TIMESTAMPS disabled). A
        // bare `new ObjectMapper()` would throw "no serializer found" for Instant —
        // exactly the kind of test-only divergence that hides production-likely bugs.
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        handler = new DashboardWebSocketHandler(incidentService, objectMapper);
    }

    @Test
    void afterConnectionEstablished_withUsername_addsSession() {
        var session = sessionWithUser("alice", "s1");

        handler.afterConnectionEstablished(session);

        assertEquals(1, handler.connectedCount());
    }

    @Test
    void afterConnectionEstablished_withoutPrincipal_closesSession() throws IOException {
        // Defense-in-depth — should never happen because the handshake interceptor
        // refuses unauthenticated connections, but the handler must self-protect.
        var session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.getId()).thenReturn("s1");

        handler.afterConnectionEstablished(session);

        assertEquals(0, handler.connectedCount());
        verify(session).close(any(CloseStatus.class));
        // No snapshot for an unauthenticated session — the bail comes before any send.
        verify(session, never()).sendMessage(any());
    }

    @Test
    void afterConnectionClosed_removesSession() {
        var session = sessionWithUser("alice", "s1");
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertEquals(0, handler.connectedCount());
    }

    @Test
    void push_sendsToAllOpenSessions() throws IOException {
        var s1 = sessionWithUser("alice", "s1");
        var s2 = sessionWithUser("bob", "s2");
        when(s1.isOpen()).thenReturn(true);
        when(s2.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(s1);
        handler.afterConnectionEstablished(s2);

        var msg = new TextMessage("{\"type\":\"INCIDENT_CRITICAL\"}");
        handler.push(msg.getPayload());

        // Exact-match verify counts only the broadcast — the snapshot's payload starts
        // with `"type":"SNAPSHOT"` and won't satisfy `eq(...)`.
        verify(s1).sendMessage(eq(msg));
        verify(s2).sendMessage(eq(msg));
    }

    @Test
    void push_skipsClosedSessions() throws IOException {
        var open = sessionWithUser("alice", "s1");
        var closed = sessionWithUser("bob", "s2");
        when(open.isOpen()).thenReturn(true);
        when(closed.isOpen()).thenReturn(false);
        handler.afterConnectionEstablished(open);
        handler.afterConnectionEstablished(closed);

        handler.push("{}");

        verify(open, atLeastOnce()).sendMessage(any());
        // Closed sessions get no snapshot (isOpen short-circuits) AND no broadcast.
        verify(closed, never()).sendMessage(any());
    }

    @Test
    void push_oneFailingSession_doesNotBlockOthers() throws IOException {
        var bad = sessionWithUser("alice", "s1");
        var good = sessionWithUser("bob", "s2");
        when(bad.isOpen()).thenReturn(true);
        when(good.isOpen()).thenReturn(true);
        // bad throws on EVERY sendMessage — both the snapshot AND the broadcast. The
        // handler swallows snapshot failures and continues, so the session still
        // registers; the broadcast attempt then also throws and is isolated.
        doThrow(new IOException("broken pipe")).when(bad).sendMessage(any());
        handler.afterConnectionEstablished(bad);
        handler.afterConnectionEstablished(good);

        var msg = new TextMessage("{}");
        handler.push(msg.getPayload());

        verify(good, times(1)).sendMessage(eq(msg));
    }

    @Test
    void handleTextMessage_droppedSilently_noStateChange() throws Exception {
        // Single-responsibility: inbound frames are NOT commands. The handler must
        // accept and discard, never dispatch.
        var session = sessionWithUser("alice", "s1");
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage("{\"command\":\"reboot\"}"));

        // Session count unchanged — nothing was forwarded, no broadcast triggered.
        assertEquals(1, handler.connectedCount());
        // Snapshot was sent at connect, but no further send happens for the inbound
        // command. We assert via "no message other than the snapshot": verify never
        // called with anything that isn't the exact-snapshot payload would be brittle,
        // so simply count: only one send to this session, which is the snapshot.
        verify(session, times(1)).sendMessage(any());
    }

    private static WebSocketSession sessionWithUser(String username, String id) {
        var session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(DashboardHandshakeInterceptor.ATTR_USERNAME, username);
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
