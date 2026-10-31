package tz.go.tirdo.cbrms.notifications;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development sender: records notices instead of sending them. Staff can read them in the console's
 * Client notices page. Replace with the approved SMS gateway and mail relay (cbrms.notifications.sender).
 */
@Component
@ConditionalOnProperty(name = "cbrms.notifications.sender", havingValue = "log", matchIfMissing = true)
public class LoggingMessageSender implements MessageSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingMessageSender.class);

    @Override
    public String send(String channel, String recipient, String body) {
        String masked = recipient.length() <= 4 ? "****" : "****" + recipient.substring(recipient.length() - 4);
        log.info("Notice not sent (log sender): {} to {}", channel, masked);
        return "LOG-" + UUID.randomUUID();
    }
}
