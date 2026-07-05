package uz.orientadvertise.services.infra.pubsub;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.ContentStatusPayload;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RedisDashboardEventBroadcasterTest {

    private static final String TOPIC = "app:dashboard:events";

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private RedisDashboardEventBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new RedisDashboardEventBroadcaster(redisTemplate, new ChannelTopic(TOPIC));
    }

    @Test
    void contentStatusChanged_publishesContentStatusChangeFrame() {
        broadcaster.contentStatusChanged(new ContentStatusPayload(
                42L, "READY", null, Instant.parse("2026-06-02T10:00:00Z")));

        var json = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq(TOPIC), json.capture());
        String frame = json.getValue();
        assertTrue(frame.contains("\"type\":\"CONTENT_STATUS_CHANGE\""), frame);
        assertTrue(frame.contains("\"contentId\":42"), frame);
        assertTrue(frame.contains("\"status\":\"READY\""), frame);
        assertTrue(frame.contains("\"invalidReason\":null"), frame);
    }

    @Test
    void contentStatusChanged_invalidReason_isEscapedAndIncluded() {
        broadcaster.contentStatusChanged(new ContentStatusPayload(
                7L, "INVALID", "bad \"codec\"", Instant.parse("2026-06-02T10:00:00Z")));

        var json = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq(TOPIC), json.capture());
        String frame = json.getValue();
        assertTrue(frame.contains("\"status\":\"INVALID\""), frame);
        assertTrue(frame.contains("\"invalidReason\":\"bad \\\"codec\\\"\""), frame);
    }

    @Test
    void contentStatusChanged_publishFailure_isSwallowed() {
        doThrow(new RuntimeException("redis down")).when(redisTemplate).convertAndSend(anyString(), any());

        assertDoesNotThrow(() -> broadcaster.contentStatusChanged(
                new ContentStatusPayload(1L, "TRANSCODING", null, Instant.parse("2026-06-02T10:00:00Z"))));
    }
}
