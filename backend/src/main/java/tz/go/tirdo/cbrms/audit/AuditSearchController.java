package tz.go.tirdo.cbrms.audit;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.cbrms.billing.BillService;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;
import tz.go.tirdo.cbrms.reports.ReportsController;

/**
 * Audit search for Finance and auditors (FR28): actor, action, record, correlation ID and date range.
 * The trail itself is append-only in the database; exports of it are audited too.
 */
@RestController
@RequestMapping("/internal/v1/admin/audit")
public class AuditSearchController {

    public record AuditRow(String id, String occurredAt, String actor, String actorName, String action, String objectRef,
                           String reason, String correlationId, String details) {}

    public record AuditPage(List<AuditRow> items, long total, int page, int size, String from, String to) {}

    private static final int MAX_DAYS = 366;
    private static final int MAX_EXPORT = 10_000;
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final JdbcClient jdbc;
    private final Actors actors;
    private final AuditService audit;

    public AuditSearchController(JdbcClient jdbc, Actors actors, AuditService audit) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.audit = audit;
    }

    @GetMapping
    public AuditPage search(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                            @RequestParam(defaultValue = "") String actor, @RequestParam(defaultValue = "") String action,
                            @RequestParam(defaultValue = "") String object, @RequestParam(defaultValue = "") String correlationId,
                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        finance();
        Filter f = filter(from, to, actor, action, object, correlationId);
        int s = Math.max(1, Math.min(size, 200));
        int p = Math.max(0, page);
        long total = bind(jdbc.sql("SELECT count(*) FROM audit_event e" + f.where()), f.params()).query(Long.class).single();
        List<AuditRow> items = bind(jdbc.sql(SELECT + f.where() + " ORDER BY e.occurred_at DESC, e.id DESC LIMIT :limit OFFSET :offset"), f.params())
                .param("limit", s).param("offset", p * s)
                .query((rs, n) -> row(rs)).list();
        return new AuditPage(items, total, p, s, f.from().toString(), f.to().toString());
    }

    /** Distinct actions seen, for the search form. */
    @GetMapping("/actions")
    public List<String> actions() {
        finance();
        return jdbc.sql("SELECT DISTINCT action FROM audit_event ORDER BY action").query(String.class).list();
    }

    @GetMapping("/export.csv")
    public ResponseEntity<String> export(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                         @RequestParam(defaultValue = "") String actor, @RequestParam(defaultValue = "") String action,
                                         @RequestParam(defaultValue = "") String object, @RequestParam(defaultValue = "") String correlationId) {
        Actor a = finance();
        Filter f = filter(from, to, actor, action, object, correlationId);
        List<AuditRow> items = bind(jdbc.sql(SELECT + f.where() + " ORDER BY e.occurred_at DESC, e.id DESC LIMIT :limit"), f.params())
                .param("limit", MAX_EXPORT + 1).query((rs, n) -> row(rs)).list();
        if (items.size() > MAX_EXPORT) {
            throw ApiException.validation("from", "more than " + MAX_EXPORT + " events match; narrow the search");
        }
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("CBRMS audit trail " + f.from() + " to " + f.to()));
        rows.add(List.of("Time (UTC)", "Actor", "Name", "Action", "Record", "Reason", "Correlation ID", "Details"));
        for (AuditRow r : items) {
            rows.add(java.util.Arrays.asList(r.occurredAt(), r.actor(), r.actorName(), r.action(), r.objectRef(), r.reason(),
                    r.correlationId(), r.details()));
        }
        audit.record(a.subject(), "AUDIT_EXPORTED", "audit:" + f.from() + ".." + f.to(), null, f.describe());
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"cbrms-audit-" + f.from() + "-to-" + f.to() + ".csv\"")
                .header("Cache-Control", "no-store")
                .contentType(CSV)
                .body(ReportsController.toCsv(rows));
    }

    // ---------------------------------------------------------------- helpers

    private static final String SELECT = """
            SELECT e.id, e.occurred_at, e.actor, u.display_name, e.action, e.object_ref, e.reason, e.correlation_id, e.details::text
              FROM audit_event e
              LEFT JOIN app_user u ON u.subject = e.actor
            """;

    private record Filter(LocalDate from, LocalDate to, String where, Map<String, Object> params, Map<String, String> describe) {}

    private static Filter filter(LocalDate from, LocalDate to, String actor, String action, String object, String correlationId) {
        LocalDate today = LocalDate.now(BillService.BUSINESS_ZONE);
        LocalDate t = to == null ? today : to;
        LocalDate f = from == null ? t.minusDays(6) : from;
        if (t.isBefore(f)) {
            throw ApiException.validation("to", "must be on or after the from date");
        }
        if (ChronoUnit.DAYS.between(f, t) >= MAX_DAYS) {
            throw ApiException.validation("to", "search at most " + MAX_DAYS + " days at a time");
        }
        StringBuilder where = new StringBuilder("""
                 WHERE e.occurred_at >= (CAST(:from AS date)::timestamp AT TIME ZONE 'Africa/Dar_es_Salaam')
                   AND e.occurred_at < ((CAST(:to AS date) + 1)::timestamp AT TIME ZONE 'Africa/Dar_es_Salaam')
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", f);
        params.put("to", t);
        Map<String, String> describe = new LinkedHashMap<>();
        if (!actor.isBlank()) {
            where.append(" AND e.actor = :actor");
            params.put("actor", actor.trim());
            describe.put("actor", actor.trim());
        }
        if (!action.isBlank()) {
            where.append(" AND e.action = :action");
            params.put("action", action.trim());
            describe.put("action", action.trim());
        }
        if (!object.isBlank()) {
            // a record reference such as bill:<id> or catalogue:LIMS-CHEM-001; a bare ID matches any record type
            where.append(" AND (e.object_ref = :object OR e.object_ref LIKE :objectPrefix OR e.object_ref LIKE :objectSuffix)");
            String o = object.trim().replace("%", "").replace("_", "\\_");
            params.put("object", object.trim());
            params.put("objectPrefix", o + "%");
            params.put("objectSuffix", "%:" + o);
            describe.put("object", object.trim());
        }
        if (!correlationId.isBlank()) {
            where.append(" AND e.correlation_id = :correlation");
            params.put("correlation", correlationId.trim());
            describe.put("correlationId", correlationId.trim());
        }
        return new Filter(f, t, where.toString(), params, describe);
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Map<String, Object> params) {
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec;
    }

    private static AuditRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AuditRow(rs.getString(1), String.valueOf(rs.getObject(2, java.time.OffsetDateTime.class)), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9));
    }

    private Actor finance() {
        Actor a = actors.current();
        if (!a.finance()) {
            throw ApiException.forbidden("CBR-AUT-006", "The audit trail is open to Finance and auditors");
        }
        return a;
    }
}
