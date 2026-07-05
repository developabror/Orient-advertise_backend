package uz.orientadvertise.services.api.ws;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.api.ws.AdminIncidentWebSocketHandler.ScopedItem;
import uz.orientadvertise.services.domain.event.IncidentPushChannel;

/**
 * Batches inbound critical-incident payloads and flushes them to all connected admin/operator
 * sessions on a fixed 1-second cadence.
 *
 * <p>Edge case: many simultaneous incidents (e.g. a cascade of devices going offline at once)
 * → buffered into a single push. Without batching, 50 critical incidents arriving in the same
 * second would fire 50 separate WebSocket frames per session; with batching, each session
 * receives one frame containing a JSON array of all 50.
 *
 * <p>Wire format: each flush is a JSON object {@code {"type":"CRITICAL_INCIDENTS","items":[...]}}.
 * If the buffer is empty when the timer fires, nothing is sent.
 */
@Component
public class BatchedIncidentBroadcaster implements IncidentPushChannel {

    private static final Logger log = LoggerFactory.getLogger(BatchedIncidentBroadcaster.class);
    private static final long FLUSH_INTERVAL_MS = 1_000L;

    private final AdminIncidentWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;
    private final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "incident-flusher");
                t.setDaemon(true);
                return t;
            });

    public BatchedIncidentBroadcaster(AdminIncidentWebSocketHandler webSocketHandler,
                                      ObjectMapper objectMapper) {
        this.webSocketHandler = webSocketHandler;
        this.objectMapper = objectMapper;
    }

    /**
     * Enqueue a serialized incident payload (JSON object string). Returns immediately —
     * delivery happens on the next 1-second tick.
     */
    @Override
    public void enqueue(String incidentJson) {
        if (incidentJson != null && !incidentJson.isBlank()) {
            buffer.offer(incidentJson);
        }
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleAtFixedRate(this::flushSafely,
                FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("Batched incident broadcaster started [interval={}ms]", FLUSH_INTERVAL_MS);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    /**
     * Called by the scheduler. Drains the buffer, packages into one JSON envelope, and
     * forwards to the WebSocket handler.
     */
    void flushSafely() {
        try {
            flush();
        } catch (Exception e) {
            log.warn("Incident flush failed (non-critical): {}", e.getMessage());
        }
    }

    void flush() {
        if (buffer.isEmpty() || webSocketHandler.connectedCount() == 0) {
            // Drain anyway when no sessions, so a runaway buffer can't grow unbounded.
            buffer.clear();
            return;
        }

        var raw = new ArrayList<String>();
        String item;
        while ((item = buffer.poll()) != null) {
            raw.add(item);
        }
        if (raw.isEmpty()) {
            return;
        }

        // Parse each buffered item to split off the server-internal "_projectId" routing field
        // (stripped from the wire JSON), then let the handler filter per item per session.
        List<ScopedItem> items = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                JsonNode node = objectMapper.readTree(s);
                if (node instanceof ObjectNode obj) {
                    JsonNode pid = obj.remove("_projectId");
                    Long projectId = (pid == null || pid.isNull()) ? null : pid.asLong();
                    items.add(new ScopedItem(projectId, objectMapper.writeValueAsString(obj)));
                } else {
                    items.add(new ScopedItem(null, s));   // not an object — admin-only routing
                }
            } catch (Exception e) {
                log.warn("Critical-incident item parse failed: {}", e.getMessage());
                items.add(new ScopedItem(null, s));        // keep it (admin-only) rather than drop
            }
        }

        var result = webSocketHandler.broadcastScopedItems(items);
        log.debug("Flushed {} incident(s) to {} sent / {} failed", items.size(),
                result.sent(), result.failed());
    }

    int bufferSize() {
        return buffer.size();
    }
}
