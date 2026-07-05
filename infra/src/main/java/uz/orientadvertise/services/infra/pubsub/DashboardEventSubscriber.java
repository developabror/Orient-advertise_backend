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
import uz.orientadvertise.services.domain.event.DashboardPushChannel;

/**
 * Subscribes to {@link RedisPubSubConfig#TOPIC_DASHBOARD} and forwards each inbound
 * message verbatim to the service-side {@link DashboardPushChannel}, which writes to all
 * connected {@code /ws/dashboard} sessions.
 *
 * <p>The push channel is looked up via {@link ObjectProvider} so infra-only test
 * slices (no service on the classpath) start cleanly — the subscriber becomes a no-op
 * instead of a wiring failure.
 */
@Component
public class DashboardEventSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(DashboardEventSubscriber.class);

    private final RedisMessageListenerContainer container;
    private final ChannelTopic dashboardEventsTopic;
    private final ObjectProvider<DashboardPushChannel> pushChannelProvider;

    public DashboardEventSubscriber(RedisMessageListenerContainer container,
                                      @Qualifier("dashboardEventsTopic") ChannelTopic dashboardEventsTopic,
                                      ObjectProvider<DashboardPushChannel> pushChannelProvider) {
        this.container = container;
        this.dashboardEventsTopic = dashboardEventsTopic;
        this.pushChannelProvider = pushChannelProvider;
    }

    @PostConstruct
    public void register() {
        container.addMessageListener(this, dashboardEventsTopic);
        log.info("Subscribed to dashboard events topic: {}", dashboardEventsTopic.getTopic());
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            var pushChannel = pushChannelProvider.getIfAvailable();
            if (pushChannel == null) {
                log.debug("No DashboardPushChannel registered — message dropped");
                return;
            }
            pushChannel.push(new String(message.getBody()));
        } catch (Exception e) {
            log.warn("Dashboard event subscriber failed to push: {}", e.getMessage());
        }
    }
}
