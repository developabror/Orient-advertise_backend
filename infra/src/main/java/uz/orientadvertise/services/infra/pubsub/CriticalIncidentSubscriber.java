package uz.orientadvertise.services.infra.pubsub;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.domain.event.IncidentPushChannel;

/**
 * Subscribes to {@code app:incidents:critical} and forwards each inbound message into
 * the service-side {@link IncidentPushChannel} for 1-second-windowed fan-out to connected
 * admin/operator WebSocket sessions.
 */
@Component
public class CriticalIncidentSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CriticalIncidentSubscriber.class);

    private final RedisMessageListenerContainer container;
    private final ChannelTopic criticalIncidentsTopic;
    // Lazy: in service-bearing contexts the BatchedIncidentBroadcaster is wired; in
    // infra-only test contexts no impl exists and the subscriber becomes a no-op.
    private final ObjectProvider<IncidentPushChannel> pushChannelProvider;

    public CriticalIncidentSubscriber(RedisMessageListenerContainer container,
                                       @Qualifier("criticalIncidentsTopic") ChannelTopic criticalIncidentsTopic,
                                       ObjectProvider<IncidentPushChannel> pushChannelProvider) {
        this.container = container;
        this.criticalIncidentsTopic = criticalIncidentsTopic;
        this.pushChannelProvider = pushChannelProvider;
    }

    @PostConstruct
    public void register() {
        container.addMessageListener(this, criticalIncidentsTopic);
        log.info("Subscribed to critical incidents topic: {}", criticalIncidentsTopic.getTopic());
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            var pushChannel = pushChannelProvider.getIfAvailable();
            if (pushChannel == null) {
                log.debug("No IncidentPushChannel registered — message dropped");
                return;
            }
            String body = new String(message.getBody());
            pushChannel.enqueue(body);
        } catch (Exception e) {
            log.warn("Critical incident subscriber failed to enqueue: {}", e.getMessage());
        }
    }
}
