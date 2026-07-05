package uz.orientadvertise.services.infra.pubsub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.domain.event.CriticalIncidentBroadcaster;

@Component
public class RedisCriticalIncidentBroadcaster implements CriticalIncidentBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RedisCriticalIncidentBroadcaster.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ChannelTopic criticalIncidentsTopic;

    public RedisCriticalIncidentBroadcaster(RedisTemplate<String, Object> redisTemplate,
                                             @Qualifier("criticalIncidentsTopic") ChannelTopic criticalIncidentsTopic) {
        this.redisTemplate = redisTemplate;
        this.criticalIncidentsTopic = criticalIncidentsTopic;
    }

    @Override
    public void broadcast(IncidentSummary summary) {
        // Hand-rolled JSON keeps this component free of an ObjectMapper dependency, which
        // isn't available in slim infra-only test slices. The fields are all primitives /
        // simple strings — no escaping subtleties beyond description, which we sanitize.
        // Leading "_projectId" is a server-internal routing field — the batched broadcaster
        // filters each item per operator session and STRIPS this field before the wire frame.
        String json = ("{\"_projectId\":%s,\"incidentId\":%d,\"deviceId\":%d,\"eventType\":\"%s\","
                + "\"priority\":\"%s\",\"description\":\"%s\",\"occurrenceCount\":%d,"
                + "\"openedAt\":\"%s\",\"updatedAt\":\"%s\"}").formatted(
                summary.projectId() == null ? "null" : summary.projectId().toString(),
                summary.incidentId(),
                summary.deviceId(),
                escape(summary.eventType()),
                escape(summary.priority()),
                escape(summary.description()),
                summary.occurrenceCount(),
                summary.openedAt(),
                summary.updatedAt());
        try {
            redisTemplate.convertAndSend(criticalIncidentsTopic.getTopic(), json);
            log.debug("Published critical incident [id={}] to {}",
                    summary.incidentId(), criticalIncidentsTopic.getTopic());
        } catch (Exception e) {
            // Pub/sub failure is non-fatal — admin can still see the incident on next page load.
            log.warn("Failed to publish critical incident [id={}]: {}",
                    summary.incidentId(), e.getMessage());
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
