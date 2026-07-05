package uz.orientadvertise.services.infra.pubsub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class EventMessageListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(EventMessageListener.class);

    @Override
    public void onMessage(Message message, byte[] pattern) {
        var channel = new String(message.getChannel());
        var body = new String(message.getBody());
        log.info("Received event on [{}]: {}", channel, body);
    }
}
