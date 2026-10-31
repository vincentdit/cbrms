package tz.go.tirdo.cbrms.events;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.cbrms.common.Json;

/**
 * Transactional outbox (FR17, FR23). Events are written in the same transaction as the financial change,
 * with a per-bill sequence, and one delivery row per active subscriber of the owning platform.
 */
@Service
public class OutboxService {
    public static final int SCHEMA_VERSION = 1;
    private final JdbcClient jdbc;

    public OutboxService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(EventSubject subject, String eventType, Map<String, ?> data) {
        UUID eventId = jdbc.sql("SELECT uuidv7()").query(UUID.class).single();
        long sequence = jdbc.sql("SELECT coalesce(max(sequence), 0) + 1 FROM outbox_event WHERE aggregate_id = :billId")
                .param("billId", subject.billId())
                .query(Long.class)
                .single();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("type", eventType);
        payload.put("billId", subject.billId());
        payload.put("sourceSystem", subject.sourceSystemCode());
        payload.put("sourceReference", subject.sourceReference());
        payload.put("sequence", sequence);
        payload.put("occurredAt", OffsetDateTime.now(ZoneOffset.UTC));
        payload.put("data", data);

        jdbc.sql("""
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, source_system_id, sequence, event_type, schema_version, payload)
                VALUES (:id, 'BILL', :billId, :sourceSystemId, :sequence, :type, :schemaVersion, CAST(:payload AS jsonb))
                """)
            .param("id", eventId)
            .param("billId", subject.billId())
            .param("sourceSystemId", subject.sourceSystemId())
            .param("sequence", sequence)
            .param("type", eventType)
            .param("schemaVersion", SCHEMA_VERSION)
            .param("payload", Json.write(payload))
            .update();

        jdbc.sql("""
                INSERT INTO event_delivery (event_id, subscriber_id)
                SELECT :eventId, s.id FROM subscriber s
                 WHERE s.source_system_id = :sourceSystemId AND s.status = 'ACTIVE'
                """)
            .param("eventId", eventId)
            .param("sourceSystemId", subject.sourceSystemId())
            .update();
        return eventId;
    }
}
