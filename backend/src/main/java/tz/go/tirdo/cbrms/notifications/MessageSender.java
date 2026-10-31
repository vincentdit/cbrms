package tz.go.tirdo.cbrms.notifications;

/** Port to the SMS and email providers. Returns the provider's message reference. */
public interface MessageSender {
    String send(String channel, String recipient, String body) throws Exception;
}
