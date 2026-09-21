package uz.orientadvertise.services.infra.pubsub;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.ContentStatusPayload;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.DeviceStatusPayload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the <b>on-the-wire</b> shape of a dashboard frame, captured at the Redis connection — the
 * layer the old test could not see.
 *
 * <p>The pre-v1.0.132 test asserted only the pre-serialisation {@code String} the broadcaster builds,
 * and passed happily while the entire live feed was dead in production. The frame is already JSON,
 * but it was published through {@code RedisTemplate<String, Object>}, whose value serializer is
 * {@link GenericJackson2JsonRedisSerializer} — so {@code convertAndSend} JSON-quoted and escaped it
 * a second time. {@code DashboardEventSubscriber} forwards the body verbatim, the browser's
 * {@code JSON.parse} then produced a <i>string</i> rather than an object, and the frontend guard
 * dropped it. {@code CONTENT_STATUS_CHANGE}, {@code DEVICE_STATUS_CHANGE}, {@code INCIDENT_CRITICAL}
 * and {@code INCIDENT_UPDATED} were all affected; only {@code SNAPSHOT} survived, because the
 * WebSocket handler serialises that one itself.
 *
 * <p>So: assert on the bytes, and assert they parse to an <b>object</b>.
 */
class DashboardFrameWireFormatTest {

    private static final String TOPIC = "app:dashboard:events";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RedisConnection connection = mock(RedisConnection.class);

    private StringRedisTemplate stringTemplate() {
        var factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        var template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    /** The template the broadcaster used to be wired with — kept here to prove the regression. */
    private RedisTemplate<String, Object> genericJsonTemplate() {
        var factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        var template = new RedisTemplate<String, Object>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    private byte[] publishedBody() {
        var body = ArgumentCaptor.forClass(byte[].class);
        verify(connection).publish(any(), body.capture());
        return body.getValue();
    }

    @Test
    void contentStatusFrame_arrivesOnTheWireAsAJsonObject() throws Exception {
        var broadcaster = new RedisDashboardEventBroadcaster(stringTemplate(), new ChannelTopic(TOPIC));

        broadcaster.contentStatusChanged(new ContentStatusPayload(
                42L, "READY", null, Instant.parse("2026-06-02T10:00:00Z"), 3L, "alice"));

        JsonNode parsed = MAPPER.readTree(new String(publishedBody(), StandardCharsets.UTF_8));
        assertTrue(parsed.isObject(), "the subscriber must be able to parse the body as an object");
        // Content frames are routed like every other event since v1.0.134 — the type/contentId the
        // browser sees live one level down, inside the envelope's payload.
        JsonNode payload = parsed.get("payload");
        assertTrue(payload.isObject(), "payload must parse as an object, not a string");
        assertEquals("CONTENT_STATUS_CHANGE", payload.get("type").asText());
        assertEquals(42, payload.get("contentId").asInt());
        assertEquals("READY", payload.get("status").asText());
        assertEquals(3, parsed.get("_projectId").asInt());
        assertEquals("alice", parsed.get("_owner").asText());
    }

    @Test
    void deviceStatusFrame_keepsItsRoutingEnvelope() throws Exception {
        // Device/incident frames are wrapped in {"_projectId":n,"payload":{...}}. Double encoding
        // broke these identically, so the envelope must survive the wire as nested objects.
        var broadcaster = new RedisDashboardEventBroadcaster(stringTemplate(), new ChannelTopic(TOPIC));

        broadcaster.deviceStatusChanged(new DeviceStatusPayload(
                7L, "ONLINE", "OFFLINE", Instant.parse("2026-06-02T10:00:00Z"), 3L));

        JsonNode parsed = MAPPER.readTree(new String(publishedBody(), StandardCharsets.UTF_8));
        assertTrue(parsed.isObject(), "envelope must parse as an object");
        assertTrue(parsed.get("payload").isObject(), "payload must parse as an object, not a string");
        assertEquals("DEVICE_STATUS_CHANGE", parsed.get("payload").get("type").asText());
    }

    @Test
    void theGenericJsonTemplate_isWhatBrokeIt_soItMustNotBeUsedHere() throws Exception {
        // Regression guard on the CAUSE, not just the symptom: publishing the very same frame
        // through the object template yields a JSON *string* on the wire. The broadcaster's
        // constructor is typed to StringRedisTemplate so this cannot be wired back by accident —
        // this test records what would happen if someone changed that type.
        var template = genericJsonTemplate();
        template.convertAndSend(TOPIC, "{\"type\":\"CONTENT_STATUS_CHANGE\",\"contentId\":42}");

        JsonNode doubleEncoded = MAPPER.readTree(new String(publishedBody(), StandardCharsets.UTF_8));
        assertTrue(doubleEncoded.isTextual(),
                "the object template double-encodes a pre-rendered JSON frame into a string — "
                        + "which is exactly why RedisDashboardEventBroadcaster takes StringRedisTemplate");
    }
}
