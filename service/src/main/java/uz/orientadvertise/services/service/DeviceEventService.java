package uz.orientadvertise.services.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.domain.event.EventPublisher;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.EventRepository;

/**
 * Centralized async emission for device state-change events.
 *
 * <p>Each emission persists an {@link Event} row and publishes a string notification via
 * {@link EventPublisher} (Redis pub/sub). Callers do not block — runs on
 * {@code auditExecutor}, which silently drops on overflow rather than back-pressuring
 * the request thread.
 *
 * <p>Edge cases:
 * <ul>
 *   <li><b>Rapid online/offline cycling</b> → in-memory sliding-window rate limiter caps
 *       emissions at {@value #MAX_EVENTS_PER_MINUTE_PER_DEVICE} per device per minute.
 *       Excess events are dropped with a WARN log; the rate limiter is per-process (no
 *       cross-instance coordination), which is sufficient since heartbeats from a single
 *       device land on a single API instance via session affinity / WebSocket pinning.</li>
 *   <li><b>Event metadata cap</b> → payload is byte-truncated to
 *       {@value #MAX_PAYLOAD_BYTES} bytes (UTF-8) with a {@code ...[truncated]} suffix
 *       so a runaway log dump can't blow up the {@code event.payload TEXT} column or the
 *       Redis broadcast message size.</li>
 * </ul>
 */
@Service
public class DeviceEventService {

    private static final Logger log = LoggerFactory.getLogger(DeviceEventService.class);

    public static final int MAX_PAYLOAD_BYTES = 10 * 1024;
    public static final int MAX_EVENTS_PER_MINUTE_PER_DEVICE = 10;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);
    private static final String TRUNCATION_SUFFIX = "...[truncated]";

    private final EventRepository eventRepository;
    private final EventPublisher eventPublisher;
    private final DeviceRepository deviceRepository;

    private final ConcurrentMap<Long, Deque<Instant>> rateLimitWindows = new ConcurrentHashMap<>();

    public DeviceEventService(EventRepository eventRepository,
                               EventPublisher eventPublisher,
                               DeviceRepository deviceRepository) {
        this.eventRepository = eventRepository;
        this.eventPublisher = eventPublisher;
        this.deviceRepository = deviceRepository;
    }

    /**
     * Fire-and-forget emission. Returns immediately; persistence + pub/sub run on the
     * audit executor pool.
     */
    @Async("auditExecutor")
    public void emitAsync(Long deviceId, String eventType, Event.Priority priority, String payload) {
        emit(deviceId, eventType, priority, payload);
    }

    /**
     * Synchronous variant used by tests and any caller that already holds an executor.
     * The same rate-limit and truncation rules apply.
     */
    @Transactional
    public boolean emit(Long deviceId, String eventType, Event.Priority priority, String payload) {
        if (!allowEmission(deviceId)) {
            log.warn("Rate limit hit for device {} ({}/min), dropping event {}",
                    deviceId, MAX_EVENTS_PER_MINUTE_PER_DEVICE, eventType);
            return false;
        }

        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            log.warn("Device {} not found, dropping event {}", deviceId, eventType);
            return false;
        }

        String safePayload = truncate(payload);

        try {
            var event = new Event(device, eventType, priority, safePayload, Instant.now());
            eventRepository.save(event);
        } catch (Exception e) {
            log.warn("Failed to persist event [device={}, type={}]: {}", deviceId, eventType, e.getMessage());
        }

        try {
            String msg = "device.event deviceId=%d type=%s priority=%s".formatted(
                    deviceId, eventType, priority);
            eventPublisher.publish(msg);
        } catch (Exception e) {
            log.debug("Pub/sub publish failed (non-critical) [device={}]: {}", deviceId, e.getMessage());
        }

        return true;
    }

    private boolean allowEmission(Long deviceId) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(RATE_WINDOW);
        Deque<Instant> window = rateLimitWindows.computeIfAbsent(deviceId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= MAX_EVENTS_PER_MINUTE_PER_DEVICE) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /**
     * Byte-truncate UTF-8 payload to MAX_PAYLOAD_BYTES with a truncation marker.
     * Cuts at byte boundary then trims any incomplete trailing UTF-8 sequence.
     */
    static String truncate(String payload) {
        if (payload == null) return null;
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_PAYLOAD_BYTES) return payload;

        int cut = MAX_PAYLOAD_BYTES;
        // Walk back from cut while the byte is a UTF-8 continuation (10xxxxxx) so we
        // don't slice through the middle of a multi-byte codepoint.
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return new String(bytes, 0, cut, StandardCharsets.UTF_8) + TRUNCATION_SUFFIX;
    }
}
