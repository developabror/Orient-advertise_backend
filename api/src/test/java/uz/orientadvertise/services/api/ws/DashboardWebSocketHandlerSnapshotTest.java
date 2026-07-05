package uz.orientadvertise.services.api.ws;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.model.Incident;
import uz.orientadvertise.services.service.IncidentService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardWebSocketHandlerSnapshotTest {

    private DashboardWebSocketHandler handler;
    private IncidentService incidentService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        incidentService = mock(IncidentService.class);
        // Mirror Spring Boot's autoconfigured ObjectMapper: JavaTimeModule registered,
        // WRITE_DATES_AS_TIMESTAMPS disabled. A bare `new ObjectMapper()` would throw
        // "no serializer found" for Instant — matching production behavior matters
        // because the snapshot frame's `serverTime` is an Instant.
        objectMapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        handler = new DashboardWebSocketHandler(incidentService, objectMapper);
    }

    /**
     * Snapshot must arrive before any live broadcast. Captures the order of sends to
     * a single session: connect → snapshot, then push → broadcast. The ordering
     * guarantee is what lets the FE drop its open-incident HTTP backfill.
     */
    @Test
    void snapshot_isFirstFrameSentToSession() throws Exception {
        when(incidentService.getOpenScoped(any())).thenReturn(List.of());
        var session = sessionWithUser("alice", "s1");

        handler.afterConnectionEstablished(session);
        // Now broadcast — the snapshot was already sent during connect.
        handler.push("{\"type\":\"INCIDENT_UPDATED\"}");

        var captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        var sends = captor.getAllValues();

        // Two sends total: snapshot then broadcast.
        assertEquals(2, sends.size(), "expected snapshot + broadcast");
        // First must be the snapshot (type=SNAPSHOT).
        var first = objectMapper.readTree(sends.get(0).getPayload());
        assertEquals("SNAPSHOT", first.get("type").asText(),
                "first frame must be the SNAPSHOT, not the broadcast");
        // Second must be the broadcast (type=INCIDENT_UPDATED).
        var second = objectMapper.readTree(sends.get(1).getPayload());
        assertEquals("INCIDENT_UPDATED", second.get("type").asText());

        // Defensive: also assert the in-order send sequence at the mock level.
        var io = inOrder(session);
        io.verify(session).sendMessage(sends.get(0));
        io.verify(session).sendMessage(sends.get(1));
    }

    /**
     * Snapshot's {@code openIncidents} array mirrors {@code GET /api/incidents/open} —
     * same field set, same shape. The FE routes both responses through one reducer.
     */
    @Test
    void snapshot_contentsMatchOpenIncidentsServiceResult() throws Exception {
        var i1 = stubIncident(101L, 7L, "DEVICE_OFFLINE", Incident.Status.OPEN,
                Event.Priority.CRITICAL, "Device 7 offline > 15min", 3,
                Instant.parse("2026-05-08T10:00:00Z"),
                Instant.parse("2026-05-08T10:30:00Z"),
                null, null, null, null);
        var i2 = stubIncident(102L, 8L, "CONTENT_VERSION_MISMATCH", Incident.Status.ACKNOWLEDGED,
                Event.Priority.MEDIUM, "Stale content on device 8", 1,
                Instant.parse("2026-05-08T11:00:00Z"),
                Instant.parse("2026-05-08T11:15:00Z"),
                Instant.parse("2026-05-08T11:15:00Z"), "alice",
                null, null);
        when(incidentService.getOpenScoped(any())).thenReturn(List.of(i1, i2));

        var session = sessionWithUser("alice", "s1");
        handler.afterConnectionEstablished(session);

        var captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        JsonNode frame = objectMapper.readTree(captor.getValue().getPayload());

        assertEquals("SNAPSHOT", frame.get("type").asText());
        assertNotNull(frame.get("serverTime"));
        // serverTime is an ISO-8601 instant — Jackson serializes Instant as that by
        // default for records (no lenient parsing needed).
        Instant.parse(frame.get("serverTime").asText());

        var openIncidents = frame.get("openIncidents");
        assertEquals(2, openIncidents.size());

        var first = openIncidents.get(0);
        assertEquals(101, first.get("id").asInt());
        assertEquals(7, first.get("deviceId").asInt());
        assertEquals("DEVICE_OFFLINE", first.get("eventType").asText());
        assertEquals("OPEN", first.get("status").asText());
        assertEquals("CRITICAL", first.get("priority").asText());
        assertEquals("Device 7 offline > 15min", first.get("description").asText());
        assertEquals(3, first.get("occurrenceCount").asInt());
        assertEquals("2026-05-08T10:00:00Z", first.get("openedAt").asText());
        assertEquals("2026-05-08T10:30:00Z", first.get("updatedAt").asText());
        assertTrue(first.get("acknowledgedAt").isNull());
        assertTrue(first.get("acknowledgedBy").isNull());
        assertTrue(first.get("resolvedAt").isNull());
        assertTrue(first.get("resolvedBy").isNull());

        var second = openIncidents.get(1);
        assertEquals("ACKNOWLEDGED", second.get("status").asText());
        assertEquals("alice", second.get("acknowledgedBy").asText());
    }

    /**
     * If {@link IncidentService#getOpen()} blows up (DB hiccup, JPA rollback) the
     * handler must register the session anyway so live deltas still flow. The FE
     * backfills via HTTP. Closing the socket would be a regression — operators would
     * lose live updates over a transient backend issue.
     */
    @Test
    void snapshot_incidentServiceFailure_doesNotBreakConnection() throws IOException {
        when(incidentService.getOpenScoped(any()))
                .thenThrow(new RuntimeException("DB unreachable"));
        var session = sessionWithUser("alice", "s1");

        handler.afterConnectionEstablished(session);

        // Session is registered even though the snapshot failed.
        assertEquals(1, handler.connectedCount());
        // No frame was sent (the failure happens before the send).
        verify(session, org.mockito.Mockito.never()).sendMessage(any());

        // Subsequent push goes through unaffected — proves the live channel still works.
        when(session.isOpen()).thenReturn(true);
        handler.push("{\"type\":\"INCIDENT_CRITICAL\"}");
        verify(session).sendMessage(any(TextMessage.class));
    }

    // ----- helpers -----

    private static WebSocketSession sessionWithUser(String username, String id) {
        var session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(DashboardHandshakeInterceptor.ATTR_USERNAME, username);
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static Incident stubIncident(long id, long deviceId, String eventType,
                                            Incident.Status status, Event.Priority priority,
                                            String description, int occurrenceCount,
                                            Instant openedAt, Instant updatedAt,
                                            Instant acknowledgedAt, String acknowledgedBy,
                                            Instant resolvedAt, String resolvedBy) {
        var device = mock(Device.class);
        when(device.getId()).thenReturn(deviceId);
        var i = mock(Incident.class);
        when(i.getId()).thenReturn(id);
        when(i.getDevice()).thenReturn(device);
        when(i.getEventType()).thenReturn(eventType);
        when(i.getStatus()).thenReturn(status);
        when(i.getPriority()).thenReturn(priority);
        when(i.getDescription()).thenReturn(description);
        when(i.getOccurrenceCount()).thenReturn(occurrenceCount);
        when(i.getOpenedAt()).thenReturn(openedAt);
        when(i.getUpdatedAt()).thenReturn(updatedAt);
        when(i.getAcknowledgedAt()).thenReturn(acknowledgedAt);
        when(i.getAcknowledgedBy()).thenReturn(acknowledgedBy);
        when(i.getResolvedAt()).thenReturn(resolvedAt);
        when(i.getResolvedBy()).thenReturn(resolvedBy);
        return i;
    }
}
