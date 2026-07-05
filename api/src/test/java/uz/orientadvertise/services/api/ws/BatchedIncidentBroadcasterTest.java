package uz.orientadvertise.services.api.ws;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.api.ws.AdminIncidentWebSocketHandler;
import uz.orientadvertise.services.api.ws.AdminIncidentWebSocketHandler.ScopedItem;
import uz.orientadvertise.services.api.ws.BatchedIncidentBroadcaster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchedIncidentBroadcasterTest {

    private AdminIncidentWebSocketHandler handler;
    private BatchedIncidentBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        handler = mock(AdminIncidentWebSocketHandler.class);
        when(handler.connectedCount()).thenReturn(1);
        when(handler.broadcastScopedItems(anyList()))
                .thenReturn(new AdminIncidentWebSocketHandler.BroadcastResult(1, 0));
        broadcaster = new BatchedIncidentBroadcaster(handler, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        broadcaster.stop();
    }

    @Test
    void emptyBuffer_doesNotBroadcast() {
        broadcaster.flush();
        verify(handler, never()).broadcastScopedItems(anyList());
    }

    @Test
    void singleIncident_packagedInEnvelope() {
        broadcaster.enqueue("{\"incidentId\":1}");
        broadcaster.flush();

        // Envelope assembly + per-session scoping now live in the handler; the broadcaster
        // hands it the per-item list (each item's _projectId stripped into ScopedItem.projectId).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScopedItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(handler).broadcastScopedItems(captor.capture());
        List<ScopedItem> items = captor.getValue();
        assertEquals(1, items.size());
        assertTrue(items.get(0).json().contains("\"incidentId\":1"));
    }

    @Test
    void multipleIncidents_batchedIntoSinglePush() {
        // Edge case: many simultaneous incidents must collapse into ONE WS frame per second.
        for (int i = 0; i < 10; i++) {
            broadcaster.enqueue("{\"incidentId\":" + i + "}");
        }
        broadcaster.flush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScopedItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(handler, times(1)).broadcastScopedItems(captor.capture());
        List<ScopedItem> items = captor.getValue();
        assertEquals(10, items.size(), "all 10 collapse into ONE per-second flush");
        String joined = items.stream().map(ScopedItem::json).reduce("", String::concat);
        // Check all 10 ids are present, single batch.
        for (int i = 0; i < 10; i++) {
            assertTrue(joined.contains("\"incidentId\":" + i),
                    "batch must contain incident " + i);
        }
        assertEquals(0, broadcaster.bufferSize(), "buffer drains on flush");
    }

    @Test
    void noConnectedSessions_drainsBufferWithoutBroadcasting() {
        when(handler.connectedCount()).thenReturn(0);

        broadcaster.enqueue("{\"incidentId\":99}");
        broadcaster.flush();

        verify(handler, never()).broadcastScopedItems(anyList());
        assertEquals(0, broadcaster.bufferSize(),
                "buffer must drain even with no listeners — prevents unbounded growth");
    }

    @Test
    void blankPayload_isNotEnqueued() {
        broadcaster.enqueue(null);
        broadcaster.enqueue("");
        broadcaster.enqueue("   ");
        assertEquals(0, broadcaster.bufferSize());
    }
}
