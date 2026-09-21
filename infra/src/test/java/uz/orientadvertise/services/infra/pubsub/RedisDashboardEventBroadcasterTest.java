package uz.orientadvertise.services.infra.pubsub;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.ContentStatusPayload;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RedisDashboardEventBroadcasterTest {

    private static final String TOPIC = "app:dashboard:events";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private RedisDashboardEventBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new RedisDashboardEventBroadcaster(redisTemplate, new ChannelTopic(TOPIC));
    }

    @Test
    void contentStatusChanged_publishesContentStatusChangeFrame() {
        broadcaster.contentStatusChanged(new ContentStatusPayload(
                42L, "READY", null, Instant.parse("2026-06-02T10:00:00Z"), 3L, "alice"));

        String frame = published();
        assertTrue(frame.contains("\"type\":\"CONTENT_STATUS_CHANGE\""), frame);
        assertTrue(frame.contains("\"contentId\":42"), frame);
        assertTrue(frame.contains("\"status\":\"READY\""), frame);
        assertTrue(frame.contains("\"invalidReason\":null"), frame);
    }

    @Test
    void contentStatusChanged_carriesTheRoutingEnvelope_soItIsNotBroadcastToEveryOperator() {
        // The defect this pins: content frames used to publish UNWRAPPED, and the dashboard handler
        // sends an unwrapped frame byte-identically to every open session — so every operator got
        // every content id, status and ffmpeg diagnostic, regardless of project or ownership.
        broadcaster.contentStatusChanged(new ContentStatusPayload(
                42L, "READY", null, Instant.parse("2026-06-02T10:00:00Z"), 3L, "alice"));

        String frame = published();
        assertTrue(frame.startsWith("{\"_projectId\":3,\"_owner\":\"alice\",\"payload\":"), frame);
    }

    @Test
    void contentStatusChanged_orphanContent_stillCarriesItsOwner() {
        // Orphan content has NO project, so the owner is the only routing key it has. Without it the
        // operator who uploaded the file would never see their own transcode finish — which is the
        // whole reason the event exists.
        broadcaster.contentStatusChanged(new ContentStatusPayload(
                42L, "TRANSCODING", null, Instant.parse("2026-06-02T10:00:00Z"), null, "alice"));

        String frame = published();
        assertTrue(frame.startsWith("{\"_projectId\":null,\"_owner\":\"alice\",\"payload\":"), frame);
    }

    @Test
    void contentStatusChanged_routingKeys_areNeverInsideThePayload() {
        // The envelope is server-internal: the handler strips it and writes only `payload` to the
        // socket. A routing key leaking INTO the payload would put the uploader's username on the
        // wire for every recipient.
        broadcaster.contentStatusChanged(new ContentStatusPayload(
                42L, "READY", null, Instant.parse("2026-06-02T10:00:00Z"), 3L, "alice"));

        String payload = published().substring(published().indexOf("\"payload\":") + 10);
        assertFalse(payload.contains("_owner"), payload);
        assertFalse(payload.contains("_projectId"), payload);
        assertFalse(payload.contains("alice"), payload);
    }

    @Test
    void contentStatusChanged_ownerIsEscaped() {
        // uploadedBy is a free-text column; an unescaped quote would produce an unparseable
        // envelope and silently kill the frame at the subscriber.
        broadcaster.contentStatusChanged(new ContentStatusPayload(
                42L, "READY", null, Instant.parse("2026-06-02T10:00:00Z"), null, "ali\"ce"));

        assertTrue(published().startsWith("{\"_projectId\":null,\"_owner\":\"ali\\\"ce\""), published());
    }

    @Test
    void contentStatusChanged_invalidReason_isEscapedAndIncluded() {
        broadcaster.contentStatusChanged(new ContentStatusPayload(
                7L, "INVALID", "bad \"codec\"", Instant.parse("2026-06-02T10:00:00Z"), 3L, "alice"));

        String frame = published();
        assertTrue(frame.contains("\"status\":\"INVALID\""), frame);
        assertTrue(frame.contains("\"invalidReason\":\"bad \\\"codec\\\"\""), frame);
    }

    @Test
    void contentStatusChanged_publishFailure_isSwallowed() {
        doThrow(new RuntimeException("redis down")).when(redisTemplate).convertAndSend(anyString(), any());

        assertDoesNotThrow(() -> broadcaster.contentStatusChanged(new ContentStatusPayload(
                1L, "TRANSCODING", null, Instant.parse("2026-06-02T10:00:00Z"), 3L, "alice")));
    }

    private String published() {
        var json = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, atLeastOnce()).convertAndSend(eq(TOPIC), json.capture());
        return json.getValue();
    }
}
