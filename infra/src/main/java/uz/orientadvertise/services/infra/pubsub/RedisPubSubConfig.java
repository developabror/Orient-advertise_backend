package uz.orientadvertise.services.infra.pubsub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class RedisPubSubConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisPubSubConfig.class);

    public static final String TOPIC_EVENTS = "app:events";
    public static final String TOPIC_INCIDENTS_CRITICAL = "app:incidents:critical";
    public static final String TOPIC_DASHBOARD = "app:dashboard:events";

    @Bean
    public ChannelTopic eventsTopic() {
        return new ChannelTopic(TOPIC_EVENTS);
    }

    @Bean
    public ChannelTopic criticalIncidentsTopic() {
        return new ChannelTopic(TOPIC_INCIDENTS_CRITICAL);
    }

    @Bean
    public ChannelTopic dashboardEventsTopic() {
        return new ChannelTopic(TOPIC_DASHBOARD);
    }

    @Bean
    public MessageListenerAdapter messageListenerAdapter(EventMessageListener listener) {
        return new MessageListenerAdapter(listener, "onMessage");
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter listenerAdapter,
            @Qualifier("eventsTopic") ChannelTopic eventsTopic) {

        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, eventsTopic);
        container.setErrorHandler(t ->
                log.warn("Pub/sub listener error (non-critical): {}", t.getMessage())
        );
        return container;
    }
}
