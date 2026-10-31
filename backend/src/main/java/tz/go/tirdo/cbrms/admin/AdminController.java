package tz.go.tirdo.cbrms.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;

/**
 * Read-only views for the Finance console (FR27, FR35). Access: Finance roles only (SecurityConfig).
 * Amounts are decimal strings; timestamps are ISO 8601.
 */
@RestController
@RequestMapping("/internal/v1")
public class AdminController {
    private static final List<String> PREPARATION = List.of("Draft", "AwaitingApproval", "Approved", "Rejected", "Superseded");
    private static final List<String> REGISTRATION = List.of("NotSubmitted", "Submitted", "AckUnknown", "AwaitingControlNumber", "Accepted",
            "Rejected", "CancellationPending", "Cancelled", "ExpiryChangePending");
    private static final List<String> COLLECTION = List.of("Unpaid", "PartiallyPaid", "Paid", "Overpaid");

    public record Me(String subject, String displayName, String kind, String sourceSystem, List<String> roles) {}

    public record BillRow(String billId, String sourceSystem, String sourceReference, String payerName, String total, String currency,
                          String controlNumber, String preparationState, String externalRegistrationStatus, String collectionState,
                          String releaseState, String createdAt, String dueDate) {}

    public record Page<T>(List<T> items, long total, int page, int size) {}

    public record PaymentRow(String paymentId, String transactionId, String amount, String allocated, String channel, String paidAt, String receivedAt) {}

    public record EventRow(long sequence, String type, String createdAt, String deliveryStatus, int attempts) {}

    public record CaseRow(String caseId, String type, String status, String billId, String sourceReference, String reference,
                          String value, String currency, String details, String dueDate, String openedAt) {}

    /** kind: BILL, CANCELLATION, EXPIRY_CHANGE or REFUND. subjectId is what the decision endpoint takes. */
    public record ApprovalRow(String kind, String subjectId, String billId, String sourceSystem, String sourceReference, String payerName,
                              String total, String currency, String description, String detail, String maker, String requestedAt) {}

    public record CreditRow(String creditId, String amount, String status, String refundId, String refundStatus) {}

    public record ChangeRow(String requestId, String kind, String status, String reason, String newDueDate, String requestedBy, String createdAt) {}

    private final JdbcClient jdbc;
    private final Actors actors;

    public AdminController(JdbcClient jdbc, Actors actors) {
        this.jdbc = jdbc;
        this.actors = actors;
    }

    /** Who is signed in and which roles they hold; used by the console to show or hide actions. */
    @GetMapping("/me")
    public Me me() {
        Actor actor = actors.current();
        List<String> roles = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .sorted()
                .toList();
        String name = jdbc.sql("SELECT display_name FROM app_user WHERE id = :id").param("id", actor.userId()).query(String.class).single();
        return new Me(actor.subject(), name, actor.kind(), actor.sourceSystemCode(), roles);
    }

    @GetMapping("/admin/summary")
    public Map<String, Object> summary() {
        requireFinance();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pendingApprovals", count("""
                SELECT count(*) FROM approval a
                 WHERE a.decision IS NULL AND a.subject_type IN ('BILL','CANCELLATION','EXPIRY_CHANGE','REFUND')
                   AND (a.subject_type <> 'BILL' OR EXISTS (SELECT 1 FROM bill b WHERE b.id = a.subject_id AND b.preparation_state = 'AwaitingApproval'))
                """));
        out.put("reconciliationToReview", count("SELECT count(*) FROM reconciliation_batch WHERE status = 'MATCHED'"));
        out.put("refundsToExecute", count("SELECT count(*) FROM refund_case WHERE status IN ('Approved','ExecutionFailed')"));
        out.put("openCases", count("SELECT count(*) FROM exception_case WHERE status <> 'CLOSED'"));
        out.put("ackUnknown", count("SELECT count(*) FROM bill WHERE registration_state = 'AckUnknown'"));
        out.put("awaitingControlNumber", count("SELECT count(*) FROM bill WHERE registration_state IN ('Submitted','AwaitingControlNumber')"));
        out.put("failedDeliveries", count("SELECT count(*) FROM event_delivery WHERE status = 'FAILED'"));
        out.put("pendingDeliveries", count("SELECT count(*) FROM event_delivery WHERE status = 'PENDING'"));
        Map<String, Object> today = jdbc.sql("""
                SELECT count(*) AS payments, coalesce(sum(amount), 0)::text AS amount
                  FROM payment
                 WHERE (received_at AT TIME ZONE 'Africa/Dar_es_Salaam')::date = (now() AT TIME ZONE 'Africa/Dar_es_Salaam')::date
                """).query().singleRow();
        out.put("collectedToday", today);
        Map<String, Object> outstanding = jdbc.sql("""
                SELECT count(*) AS bills,
                       coalesce(sum(b.total - coalesce((SELECT sum(a.amount) FROM payment_allocation a WHERE a.bill_id = b.id), 0)), 0)::text AS amount
                  FROM bill b
                 WHERE b.registration_state = 'Accepted' AND b.collection_state IN ('Unpaid','PartiallyPaid')
                """).query().singleRow();
        out.put("outstanding", outstanding);
        out.put("byCollection", groupCounts("collection_state", "WHERE registration_state = 'Accepted'"));
        out.put("byRegistration", groupCounts("registration_state", ""));
        return out;
    }

    public record PlatformRow(String code, String name, String status, long bills, List<String> services) {}

    /** Source platforms registered with CBRMS and the services each may bill. */
    @GetMapping("/admin/platforms")
    public List<PlatformRow> platforms() {
        requireFinance();
        return jdbc.sql("""
                SELECT s.code, s.name, s.status,
                       (SELECT count(*) FROM bill b WHERE b.source_system_id = s.id),
                       coalesce((SELECT string_agg(v.code, ',' ORDER BY v.code)
                                   FROM source_system_service m JOIN service v ON v.id = m.service_id
                                  WHERE m.source_system_id = s.id), '')
                  FROM source_system s
                 ORDER BY s.code
                """)
            .query((rs, n) -> new PlatformRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                    rs.getString(5).isEmpty() ? List.of() : List.of(rs.getString(5).split(","))))
            .list();
    }

    @GetMapping("/admin/bills")
    public Page<BillRow> bills(@RequestParam(defaultValue = "") String search,
                               @RequestParam(defaultValue = "") String preparation,
                               @RequestParam(defaultValue = "") String registration,
                               @RequestParam(defaultValue = "") String collection,
                               @RequestParam(defaultValue = "") String source,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "25") int size) {
        requireFinance();
        int boundedSize = Math.max(1, Math.min(size, 100));
        int boundedPage = Math.max(0, page);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (!search.isBlank()) {
            where.append(" AND (b.source_reference ILIKE :q OR e.control_number LIKE :qcn OR p.display_name ILIKE :q OR b.id::text = :exact)");
            params.put("q", "%" + search.trim() + "%");
            params.put("qcn", search.replaceAll("\\s", "") + "%");
            params.put("exact", search.trim());
        }
        filter(where, params, "b.preparation_state", "preparation", preparation, PREPARATION);
        filter(where, params, "b.registration_state", "registration", registration, REGISTRATION);
        filter(where, params, "b.collection_state", "collection", collection, COLLECTION);
        if (!source.isBlank()) {
            if (!source.matches("[a-z][a-z0-9-]{1,30}")) {
                throw ApiException.validation("source", "unknown platform " + source);
            }
            where.append(" AND s.code = :source");
            params.put("source", source);
        }
        String from = """
                 FROM bill b
                 JOIN source_system s ON s.id = b.source_system_id
                 JOIN payer p ON p.id = b.payer_id
                 LEFT JOIN external_bill e ON e.bill_id = b.id
                """;
        JdbcClient.StatementSpec countSpec = jdbc.sql("SELECT count(*)" + from + where);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            countSpec = countSpec.param(e.getKey(), e.getValue());
        }
        long total = countSpec.query(Long.class).single();
        JdbcClient.StatementSpec spec = jdbc.sql("""
                SELECT b.id, s.code, b.source_reference, p.display_name, b.total, b.currency, e.control_number,
                       b.preparation_state, b.registration_state, b.collection_state, b.release_state, b.created_at, b.due_date
                """ + from + where + " ORDER BY b.created_at DESC LIMIT :limit OFFSET :offset");
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        List<BillRow> items = spec.param("limit", boundedSize).param("offset", boundedPage * boundedSize)
                .query((rs, n) -> new BillRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5).toPlainString(), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), String.valueOf(rs.getObject(12, java.time.OffsetDateTime.class)),
                        String.valueOf(rs.getObject(13))))
                .list();
        return new Page<>(items, total, boundedPage, boundedSize);
    }

    @GetMapping("/admin/bills/{billId}/activity")
    public Map<String, Object> activity(@PathVariable UUID billId) {
        requireFinance();
        if (jdbc.sql("SELECT count(*) FROM bill WHERE id = :id").param("id", billId).query(Long.class).single() == 0) {
            throw ApiException.notFound("Bill not found");
        }
        List<PaymentRow> payments = jdbc.sql("""
                SELECT p.id, p.external_transaction_id, p.amount,
                       coalesce((SELECT sum(a.amount) FROM payment_allocation a WHERE a.payment_id = p.id AND a.bill_id = :id), 0),
                       p.channel, p.occurred_at, p.received_at
                  FROM payment p
                 WHERE p.id IN (SELECT payment_id FROM payment_allocation WHERE bill_id = :id
                                UNION SELECT payment_id FROM credit_balance WHERE bill_id = :id)
                 ORDER BY p.received_at
                """)
            .param("id", billId)
            .query((rs, n) -> new PaymentRow(rs.getString(1), rs.getString(2), rs.getBigDecimal(3).toPlainString(),
                    rs.getBigDecimal(4).toPlainString(), rs.getString(5),
                    String.valueOf(rs.getObject(6, java.time.OffsetDateTime.class)), String.valueOf(rs.getObject(7, java.time.OffsetDateTime.class))))
            .list();
        List<EventRow> events = jdbc.sql("""
                SELECT e.sequence, e.event_type, e.created_at,
                       coalesce((SELECT d.status FROM event_delivery d WHERE d.event_id = e.id ORDER BY d.id LIMIT 1), 'NO_SUBSCRIBER'),
                       coalesce((SELECT max(d.attempts) FROM event_delivery d WHERE d.event_id = e.id), 0)
                  FROM outbox_event e
                 WHERE e.aggregate_id = :id
                 ORDER BY e.sequence
                """)
            .param("id", billId)
            .query((rs, n) -> new EventRow(rs.getLong(1), rs.getString(2), String.valueOf(rs.getObject(3, java.time.OffsetDateTime.class)),
                    rs.getString(4), rs.getInt(5)))
            .list();
        List<CaseRow> cases = cases("", billId);
        String credit = jdbc.sql("SELECT coalesce(sum(amount), 0)::text FROM credit_balance WHERE bill_id = :id AND status = 'AVAILABLE'")
                .param("id", billId).query(String.class).single();
        String allocated = jdbc.sql("SELECT coalesce(sum(amount), 0)::text FROM payment_allocation WHERE bill_id = :id")
                .param("id", billId).query(String.class).single();
        List<CreditRow> credits = jdbc.sql("""
                SELECT c.id, c.amount, c.status, f.id, f.status
                  FROM credit_balance c
                  LEFT JOIN LATERAL (SELECT id, status FROM refund_case WHERE credit_balance_id = c.id ORDER BY created_at DESC LIMIT 1) f ON true
                 WHERE c.bill_id = :id ORDER BY c.created_at
                """)
            .param("id", billId)
            .query((rs, n) -> new CreditRow(rs.getString(1), rs.getBigDecimal(2).toPlainString(), rs.getString(3), rs.getString(4), rs.getString(5)))
            .list();
        List<ChangeRow> changes = jdbc.sql("""
                SELECT r.id, r.kind, r.status, r.reason, (r.new_valid_until AT TIME ZONE 'Africa/Dar_es_Salaam')::date, u.display_name, r.created_at
                  FROM bill_change_request r JOIN app_user u ON u.id = r.requested_by
                 WHERE r.bill_id = :id ORDER BY r.created_at DESC
                """)
            .param("id", billId)
            .query((rs, n) -> new ChangeRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getObject(5) == null ? null : String.valueOf(rs.getObject(5)), rs.getString(6),
                    String.valueOf(rs.getObject(7, java.time.OffsetDateTime.class))))
            .list();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("credits", credits);
        out.put("changes", changes);
        out.put("payments", payments);
        out.put("events", events);
        out.put("cases", cases);
        out.put("allocated", allocated);
        out.put("credit", credit);
        return out;
    }

    @GetMapping("/admin/approvals")
    public List<ApprovalRow> approvals() {
        requireFinance();
        return jdbc.sql("""
                SELECT a.subject_type,
                       a.subject_id,
                       b.id, s.code, b.source_reference, p.display_name, b.total, b.currency, b.description,
                       CASE a.subject_type
                         WHEN 'BILL' THEN NULL
                         WHEN 'REFUND' THEN 'Refund of ' || f.requested_amount::text || ' ' || b.currency || ': ' || f.reason
                         WHEN 'CANCELLATION' THEN 'Cancel control number: ' || r.reason
                         ELSE 'New deadline ' || to_char(r.new_valid_until AT TIME ZONE 'Africa/Dar_es_Salaam', 'DD Mon YYYY') || ': ' || r.reason
                       END,
                       u.display_name, a.created_at
                  FROM approval a
                  JOIN app_user u ON u.id = a.maker_id
                  LEFT JOIN bill_change_request r ON r.id = a.subject_id AND a.subject_type IN ('CANCELLATION','EXPIRY_CHANGE')
                  LEFT JOIN refund_case f ON f.id = a.subject_id AND a.subject_type = 'REFUND'
                  LEFT JOIN credit_balance cb ON cb.id = f.credit_balance_id
                  JOIN bill b ON b.id = CASE a.subject_type WHEN 'BILL' THEN a.subject_id
                                                            WHEN 'REFUND' THEN cb.bill_id
                                                            ELSE r.bill_id END
                  JOIN source_system s ON s.id = b.source_system_id
                  JOIN payer p ON p.id = b.payer_id
                 WHERE a.decision IS NULL
                   AND a.subject_type IN ('BILL','CANCELLATION','EXPIRY_CHANGE','REFUND')
                   AND (a.subject_type <> 'BILL' OR b.preparation_state = 'AwaitingApproval')
                 ORDER BY a.created_at
                """)
            .query((rs, n) -> new ApprovalRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getString(6), rs.getBigDecimal(7).toPlainString(), rs.getString(8), rs.getString(9), rs.getString(10),
                    rs.getString(11), String.valueOf(rs.getObject(12, java.time.OffsetDateTime.class))))
            .list();
    }

    @GetMapping("/admin/cases")
    public List<CaseRow> openCases(@RequestParam(defaultValue = "OPEN") String status) {
        requireFinance();
        if (!List.of("OPEN", "IN_PROGRESS", "CLOSED", "ALL").contains(status)) {
            throw ApiException.validation("status", "must be OPEN, IN_PROGRESS, CLOSED or ALL");
        }
        return cases(status, null);
    }

    private List<CaseRow> cases(String status, UUID billId) {
        String sql = """
                SELECT c.id, c.type, c.status, c.bill_id, b.source_reference, c.reference, c.value, c.currency, c.details, c.due_date, c.opened_at
                  FROM exception_case c
                  LEFT JOIN bill b ON b.id = c.bill_id
                 WHERE (CAST(:billId AS uuid) IS NULL OR c.bill_id = CAST(:billId AS uuid))
                   AND (:status = '' OR :status = 'ALL' OR c.status = :status OR (:status = 'OPEN' AND c.status = 'IN_PROGRESS'))
                 ORDER BY c.opened_at DESC
                 LIMIT 200
                """;
        return jdbc.sql(sql)
            .param("billId", billId == null ? null : billId.toString())
            .param("status", status)
            .query((rs, n) -> new CaseRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getString(6), rs.getBigDecimal(7) == null ? null : rs.getBigDecimal(7).toPlainString(), rs.getString(8),
                    rs.getString(9), rs.getObject(10) == null ? null : String.valueOf(rs.getObject(10)),
                    String.valueOf(rs.getObject(11, java.time.OffsetDateTime.class))))
            .list();
    }

    private void requireFinance() {
        if (!actors.current().finance()) {
            throw ApiException.forbidden("CBR-AUT-006", "Finance console access requires a Finance role");
        }
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private Map<String, Long> groupCounts(String column, String where) {
        List<Object[]> rows = jdbc.sql("SELECT " + column + ", count(*) FROM bill " + where + " GROUP BY " + column)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getLong(2)})
                .list();
        return rows.stream().collect(Collectors.toMap(r -> (String) r[0], r -> (Long) r[1], (a, b) -> a, LinkedHashMap::new));
    }

    private static void filter(StringBuilder where, Map<String, Object> params, String column, String name, String value, List<String> allowed) {
        if (value.isBlank()) {
            return;
        }
        List<String> values = new ArrayList<>();
        for (String v : value.split(",")) {
            if (!allowed.contains(v.trim())) {
                throw ApiException.validation(name, "unknown state " + v.trim());
            }
            values.add(v.trim());
        }
        where.append(" AND ").append(column).append(" IN (:").append(name).append(")");
        params.put(name, values);
    }
}
