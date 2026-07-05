package uz.orientadvertise.services.infra.pubsub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ResilientEventPublisherTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ResilientEventPublisher publisher;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        var topic = new ChannelTopic("app:events");
        publisher = new ResilientEventPublisher(redisTemplate, topic);
    }

    @Test
    void publish_sendsMessageToTopic() {
        var result = publisher.publish("test-event");
        assertTrue(result);
        verify(redisTemplate).convertAndSend(eq("app:events"), eq("test-event"));
    }

    @Test
    void publish_returnsFalseOnConnectionFailure() {
        doThrow(new RedisConnectionFailureException("Connection refused"))
                .when(redisTemplate).convertAndSend(any(String.class), any());
        var result = publisher.publish("test-event");
        assertFalse(result);
    }

    @Test
    void publish_doesNotThrowOnConnectionFailure() {
        doThrow(new RedisConnectionFailureException("Connection refused"))
                .when(redisTemplate).convertAndSend(any(String.class), any());
        assertDoesNotThrow(() -> publisher.publish("test-event"));
    }

    @Test
    void publish_returnsFalseOnUnexpectedException() {
        doThrow(new RuntimeException("Unexpected"))
                .when(redisTemplate).convertAndSend(any(String.class), any());
        var result = publisher.publish("test-event");
        assertFalse(result);
    }
}
