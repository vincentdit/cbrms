package tz.go.tirdo.cbrms.billing;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Idempotency-Key records scoped to client and operation (Blueprint section 8). */
@Repository
public class IdempotencyRepository {
    public record Entry(String requestDigest, UUID resourceId) {}

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Entry> find(String clientId, String operation, String key) {
        return jdbc.sql("""
                SELECT request_digest, resource_id FROM idempotency_record
                 WHERE client_id = :client AND operation = :op AND idem_key = :key
                """)
            .param("client", clientId)
            .param("op", operation)
            .param("key", key)
            .query((rs, n) -> new Entry(rs.getString(1), rs.getObject(2, UUID.class)))
            .optional();
    }

    /** Returns false if another request stored the same key first. */
    public boolean save(String clientId, String operation, String key, String digest, UUID resourceId, Duration retention) {
        return jdbc.sql("""
                INSERT INTO idempotency_record (client_id, operation, idem_key, request_digest, resource_id, expires_at)
                VALUES (:client, :op, :key, :digest, :resource, now() + CAST(:retention AS interval))
                ON CONFLICT DO NOTHING
                """)
            .param("client", clientId)
            .param("op", operation)
            .param("key", key)
            .param("digest", digest)
            .param("resource", resourceId)
            .param("retention", retention.toSeconds() + " seconds")
            .update() == 1;
    }
}
