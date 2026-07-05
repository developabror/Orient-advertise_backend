package uz.orientadvertise.services.infra.pubsub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.domain.event.EventPublisher;

@Component
public class ResilientEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ResilientEventPublisher.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ChannelTopic eventsTopic;

    public ResilientEventPublisher(RedisTemplate<String, Object> redisTemplate,
                                    @Qualifier("eventsTopic") ChannelTopic eventsTopic) {
        this.redisTemplate = redisTemplate;
        this.eventsTopic = eventsTopic;
    }

    public boolean publish(String message) {
        try {
            redisTemplate.convertAndSend(eventsTopic.getTopic(), message);
            log.debug("Published event to [{}]: {}", eventsTopic.getTopic(), message);
            return true;
        } catch (RedisConnectionFailureException e) {
            log.warn("Failed to publish event (Redis unavailable): {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Failed to publish event: {}", e.getMessage());
            return false;
        }
    }
}
