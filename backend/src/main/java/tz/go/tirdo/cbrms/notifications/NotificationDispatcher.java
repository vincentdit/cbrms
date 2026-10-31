package tz.go.tirdo.cbrms.notifications;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.go.tirdo.cbrms.domain.Backoff;

/** Sends queued client notices with leases and retries. A failed notice never affects financial records. */
@Component
public class NotificationDispatcher {
    private static final int MAX_ATTEMPTS = 6;

    record Claimed(UUID id, int attempts, String channel, String recipient, String body) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final MessageSender sender;
    private final Backoff backoff = new Backoff(Duration.ofSeconds(30), Duration.ofMinutes(30), 0.2);

    public NotificationDispatcher(JdbcClient jdbc, TransactionTemplate tx, MessageSender sender) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${cbrms.notifications.dispatch-interval:PT5S}")
    public void scheduled() {
        runOnce();
    }

    public int runOnce() {
        List<Claimed> batch = tx.execute(status -> jdbc.sql("""
                UPDATE client_notification n
                   SET lease_until = now() + interval '1 minute', attempts = n.attempts + 1
                 WHERE n.id IN (SELECT id FROM client_notification
                                 WHERE status = 'PENDING' AND next_attempt_at <= now()
                                   AND (lease_until IS NULL OR lease_until < now())
                                 ORDER BY next_attempt_at
                                 LIMIT 50
                                 FOR UPDATE SKIP LOCKED)
                RETURNING n.id, n.attempts, n.channel, n.recipient, n.body
                """)
            .query((rs, n) -> new Claimed(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5)))
            .list());
        if (batch == null) {
            return 0;
        }
        for (Claimed c : batch) {
            try {
                String ref = sender.send(c.channel(), c.recipient(), c.body());
                jdbc.sql("UPDATE client_notification SET status = 'SENT', sent_at = now(), provider_ref = :ref, lease_until = NULL WHERE id = :id")
                    .param("ref", ref)
                    .param("id", c.id())
                    .update();
            } catch (Exception e) {
                String error = (e.getClass().getSimpleName() + ": " + e.getMessage());
                boolean last = c.attempts() >= MAX_ATTEMPTS;
                jdbc.sql("""
                        UPDATE client_notification
                           SET status = :status, lease_until = NULL, last_error = :error,
                               next_attempt_at = now() + CAST(:delay AS interval)
                         WHERE id = :id
                        """)
                    .param("status", last ? "FAILED" : "PENDING")
                    .param("error", error.substring(0, Math.min(error.length(), 500)))
                    .param("delay", backoff.delay(c.attempts(), ThreadLocalRandom.current()).toMillis() + " milliseconds")
                    .param("id", c.id())
                    .update();
            }
        }
        return batch.size();
    }
}
