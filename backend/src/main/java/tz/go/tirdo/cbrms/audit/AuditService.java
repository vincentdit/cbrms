package tz.go.tirdo.cbrms.audit;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tz.go.tirdo.cbrms.common.Correlation;
import tz.go.tirdo.cbrms.common.Json;

/** Append-only audit trail (FR28). Joins the caller's transaction so audit and change commit together. */
@Service
public class AuditService {
    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String objectRef, String reason, Map<String, ?> details) {
        jdbc.sql("""
                INSERT INTO audit_event (actor, action, object_ref, reason, correlation_id, details)
                VALUES (:actor, :action, :objectRef, :reason, :correlationId, CAST(:details AS jsonb))
                """)
            .param("actor", actor)
            .param("action", action)
            .param("objectRef", objectRef)
            .param("reason", reason)
            .param("correlationId", Correlation.current())
            .param("details", details == null ? null : Json.write(details))
            .update();
    }
}
