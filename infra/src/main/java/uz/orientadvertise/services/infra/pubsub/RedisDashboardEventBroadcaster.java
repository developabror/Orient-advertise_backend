package uz.orientadvertise.services.infra.pubsub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster;

/**
 * Publishes dashboard events to {@link RedisPubSubConfig#TOPIC_DASHBOARD}. Each message
 * is hand-rolled JSON tagged with a {@code type} discriminator the WebSocket consumer
 * dispatches on. Hand-rolling avoids an {@code ObjectMapper} dependency in the slim
 * infra-only test slice — the fields are primitives or escapeable strings.
 *
 * <p>Pub/sub failures are logged at {@code warn} but never thrown — the dashboard live
 * feed is best-effort. Operators get the same data on the next page load via the
 * {@code /service/dashboard/summary} endpoint.
 */
@Component
public class RedisDashboardEventBroadcaster implements DashboardEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RedisDashboardEventBroadcaster.class);

    public static final String TYPE_INCIDENT_CRITICAL = "INCIDENT_CRITICAL";
    public static final String TYPE_INCIDENT_UPDATED = "INCIDENT_UPDATED";
    public static final String TYPE_DEVICE_STATUS_CHANGE = "DEVICE_STATUS_CHANGE";
    public static final String TYPE_CONTENT_STATUS_CHANGE = "CONTENT_STATUS_CHANGE";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ChannelTopic dashboardEventsTopic;

    public RedisDashboardEventBroadcaster(RedisTemplate<String, Object> redisTemplate,
                                            @Qualifier("dashboardEventsTopic") ChannelTopic dashboardEventsTopic) {
        this.redisTemplate = redisTemplate;
        this.dashboardEventsTopic = dashboardEventsTopic;
    }

    @Override
    public void incidentCritical(IncidentPayload p) {
        publish(envelope(p.projectId(), incidentJson(TYPE_INCIDENT_CRITICAL, p)));
    }

    @Override
    public void incidentUpdated(IncidentPayload p) {
        publish(envelope(p.projectId(), incidentJson(TYPE_INCIDENT_UPDATED, p)));
    }

    @Override
    public void deviceStatusChanged(DeviceStatusPayload p) {
        String json = ("{\"type\":\"%s\",\"deviceId\":%d,\"oldStatus\":\"%s\","
                + "\"newStatus\":\"%s\",\"changedAt\":\"%s\"}").formatted(
                TYPE_DEVICE_STATUS_CHANGE,
                p.deviceId(),
                escape(p.oldStatus()),
                escape(p.newStatus()),
                p.changedAt());
        publish(envelope(p.projectId(), json));
    }

    @Override
    public void contentStatusChanged(ContentStatusPayload p) {
        String json = ("{\"type\":\"%s\",\"contentId\":%d,\"status\":\"%s\","
                + "\"invalidReason\":%s,\"at\":\"%s\"}").formatted(
                TYPE_CONTENT_STATUS_CHANGE,
                p.contentId(),
                escape(p.status()),
                p.invalidReason() == null ? "null" : "\"" + escape(p.invalidReason()) + "\"",
                p.at());
        publish(json);
    }

    private static String incidentJson(String type, IncidentPayload p) {
        // openedAt and updatedAt are nullable defensively (e.g. brand-new incident with
        // updatedAt unset would NPE on String.format), so render explicit "null" when absent.
        return ("{\"type\":\"%s\",\"incidentId\":%d,\"deviceId\":%d,\"eventType\":\"%s\","
                + "\"status\":\"%s\",\"priority\":\"%s\",\"description\":\"%s\","
                + "\"openedAt\":%s,\"updatedAt\":%s,\"actor\":%s}").formatted(
                type,
                p.incidentId(),
                p.deviceId(),
                escape(p.eventType()),
                escape(p.status()),
                escape(p.priority()),
                escape(p.description()),
                p.openedAt() == null ? "null" : "\"" + p.openedAt() + "\"",
                p.updatedAt() == null ? "null" : "\"" + p.updatedAt() + "\"",
                p.actor() == null ? "null" : "\"" + escape(p.actor()) + "\"");
    }

    /**
     * Wrap a device/incident frame in the server-internal routing envelope
     * {@code {"_projectId":<num|null>,"payload":<frame>}}. The dashboard handler reads
     * {@code _projectId} to fan out per operator session and writes ONLY {@code payload} to
     * the socket — so the on-the-wire frame contract is unchanged. Content frames are NOT
     * wrapped (they broadcast to every session).
     */
    private static String envelope(Long projectId, String frame) {
        return "{\"_projectId\":%s,\"payload\":%s}".formatted(
                projectId == null ? "null" : projectId.toString(), frame);
    }

    private void publish(String json) {
        try {
            redisTemplate.convertAndSend(dashboardEventsTopic.getTopic(), json);
        } catch (Exception e) {
            log.warn("Failed to publish dashboard event: {}", e.getMessage());
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
