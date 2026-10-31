package tz.go.tirdo.cbrms.events;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.go.tirdo.cbrms.cases.CaseService;
import tz.go.tirdo.cbrms.common.HmacSigner;
import tz.go.tirdo.cbrms.config.CbrmsProperties;
import tz.go.tirdo.cbrms.domain.Backoff;

/**
 * Delivers committed outbox events to platform endpoints with HMAC signatures (FR23, FR24, FR36).
 * Deliveries are claimed with a lease and SKIP LOCKED, so several instances never send the same delivery at once,
 * and a crashed worker's lease simply expires.
 */
@Component
public class EventDispatcher {
    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    record Claimed(UUID deliveryId, int attempts, UUID eventId, String eventType, String payload, String endpoint, String keyId) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final CbrmsProperties props;
    private final CaseService cases;
    private final HttpClient http;
    private final Backoff backoff;

    public EventDispatcher(JdbcClient jdbc, TransactionTemplate tx, CbrmsProperties props, CaseService cases) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.props = props;
        this.cases = cases;
        this.http = HttpClient.newBuilder().connectTimeout(props.events().httpTimeout()).build();
        this.backoff = new Backoff(props.events().baseBackoff(), props.events().maxBackoff(), 0.2);
    }

    @Scheduled(fixedDelayString = "${cbrms.events.dispatch-interval:PT2S}")
    public void scheduled() {
        runOnce();
    }

    /** Claims and delivers one batch. Returns the number of deliveries attempted. */
    public int runOnce() {
        List<Claimed> batch = tx.execute(status -> claim());
        if (batch == null) {
            return 0;
        }
        for (Claimed c : batch) {
            deliver(c);
        }
        return batch.size();
    }

    private List<Claimed> claim() {
        return jdbc.sql("""
                WITH c AS (
                  UPDATE event_delivery d
                     SET lease_until = now() + CAST(:lease AS interval), attempts = d.attempts + 1
                   WHERE d.id IN (SELECT id FROM event_delivery
                                   WHERE status = 'PENDING' AND next_attempt_at <= now()
                                     AND (lease_until IS NULL OR lease_until < now())
                                   ORDER BY next_attempt_at
                                   LIMIT :batch
                                   FOR UPDATE SKIP LOCKED)
                  RETURNING d.id, d.attempts, d.event_id, d.subscriber_id)
                SELECT c.id, c.attempts, e.id, e.event_type, e.payload::text, s.endpoint_url, s.hmac_key_id
                  FROM c
                  JOIN outbox_event e ON e.id = c.event_id
                  JOIN subscriber s ON s.id = c.subscriber_id
                 ORDER BY e.position
                """)
            .param("lease", props.events().lease().toSeconds() + " seconds")
            .param("batch", props.events().batchSize())
            .query((rs, n) -> new Claimed(rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, UUID.class),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)))
            .list();
    }

    private void deliver(Claimed c) {
        Integer statusCode = null;
        String error = null;
        try {
            String key = props.webhookKeys() == null ? null : props.webhookKeys().get(c.keyId());
            if (key == null) {
                error = "No signing key configured for key id " + c.keyId();
            } else {
                long ts = Instant.now().getEpochSecond();
                String signature = HmacSigner.sign(key.getBytes(StandardCharsets.UTF_8), ts, c.payload());
                HttpRequest request = HttpRequest.newBuilder(URI.create(c.endpoint()))
                        .timeout(props.events().httpTimeout())
                        .header("Content-Type", "application/json")
                        .header("X-CBRMS-Event-Id", c.eventId().toString())
                        .header("X-CBRMS-Event-Type", c.eventType())
                        .header("X-CBRMS-Timestamp", Long.toString(ts))
                        .header("X-CBRMS-Key-Id", c.keyId())
                        .header("X-CBRMS-Signature", signature)
                        .POST(HttpRequest.BodyPublishers.ofString(c.payload(), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                statusCode = response.statusCode();
                if (statusCode >= 200 && statusCode < 300) {
                    markDelivered(c, statusCode);
                    return;
                }
                error = "HTTP " + statusCode;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        markFailed(c, statusCode, error);
    }

    private void markDelivered(Claimed c, int statusCode) {
        jdbc.sql("""
                UPDATE event_delivery
                   SET status = 'DELIVERED', delivered_at = now(), lease_until = NULL, last_status_code = :code, last_error = NULL
                 WHERE id = :id
                """)
            .param("code", statusCode)
            .param("id", c.deliveryId())
            .update();
    }

    private void markFailed(Claimed c, Integer statusCode, String error) {
        String trimmed = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        if (c.attempts() >= props.events().maxAttempts()) {
            tx.executeWithoutResult(status -> {
                jdbc.sql("""
                        UPDATE event_delivery SET status = 'FAILED', lease_until = NULL, last_status_code = :code, last_error = :error
                         WHERE id = :id
                        """)
                    .param("code", statusCode)
                    .param("error", trimmed)
                    .param("id", c.deliveryId())
                    .update();
                UUID billId = jdbc.sql("SELECT aggregate_id FROM outbox_event WHERE id = :id").param("id", c.eventId()).query(UUID.class).single();
                cases.openOnceForBill("DELIVERY_FAILED", billId, "Event " + c.eventId() + " not delivered after " + c.attempts() + " attempts: " + trimmed);
            });
            log.warn("Event delivery {} failed permanently after {} attempts", c.deliveryId(), c.attempts());
            return;
        }
        Duration delay = backoff.delay(c.attempts(), ThreadLocalRandom.current());
        jdbc.sql("""
                UPDATE event_delivery
                   SET lease_until = NULL, last_status_code = :code, last_error = :error,
                       next_attempt_at = now() + CAST(:delay AS interval)
                 WHERE id = :id
                """)
            .param("code", statusCode)
            .param("error", trimmed)
            .param("delay", delay.toMillis() + " milliseconds")
            .param("id", c.deliveryId())
            .update();
    }

    /** Operational metric source: age of the oldest pending delivery (NFR09 alert at 5 minutes). */
    public Map<String, Object> health() {
        return jdbc.sql("""
                SELECT count(*) AS pending, coalesce(extract(epoch FROM now() - min(next_attempt_at)), 0) AS oldest_seconds
                  FROM event_delivery WHERE status = 'PENDING'
                """)
            .query()
            .singleRow();
    }
}
