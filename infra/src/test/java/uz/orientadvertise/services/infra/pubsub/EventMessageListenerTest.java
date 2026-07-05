package uz.orientadvertise.services.infra.pubsub;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class EventMessageListenerTest {

    private final EventMessageListener listener = new EventMessageListener();

    @Test
    void onMessage_processesMessageWithoutException() {
        Message message = new DefaultMessage(
                "app:events".getBytes(),
                "test-event-payload".getBytes()
        );
        assertDoesNotThrow(() -> listener.onMessage(message, null));
    }

    @Test
    void onMessage_handlesEmptyBody() {
        Message message = new DefaultMessage(
                "app:events".getBytes(),
                "".getBytes()
        );
        assertDoesNotThrow(() -> listener.onMessage(message, null));
    }
}
