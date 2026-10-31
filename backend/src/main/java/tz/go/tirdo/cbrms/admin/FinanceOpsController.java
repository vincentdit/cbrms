package tz.go.tirdo.cbrms.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;
import tz.go.tirdo.cbrms.reconciliation.ReconciliationService;
import tz.go.tirdo.cbrms.refunds.RefundService;

/** Finance operations: reconciliation runs, refunds and client notices (Finance roles only, see SecurityConfig). */
@RestController
@RequestMapping("/internal/v1")
public class FinanceOpsController {

    public record RunRequest(@NotNull LocalDate businessDate) {}

    public record ReviewRequest(@Size(max = 500) String note) {}

    public record RefundRequest(@NotNull UUID creditBalanceId, @NotNull @Positive BigDecimal amount, @NotBlank @Size(max = 500) String reason) {}

    public record Decision(@NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision, @Size(max = 500) String reason) {}

    public record ExecutionRequest(@Size(max = 100) String executionRef, @Size(max = 500) String failureReason) {}

    public record RunRow(String batchId, String businessDate, String status, int matched, int exceptions, int missingInGepg,
                         String externalTotal, String localTotal, String importedAt, String reviewer, String reviewedAt, String note) {}

    public record ItemRow(String reference, String controlNumber, String amount, String currency, String matchStatus, String caseId) {}

    public record RefundRow(String refundId, String status, String amount, String reason, String billId, String sourceReference,
                            String payerName, String requestedBy, String executionRef, String failureReason, String createdAt) {}

    public record NoticeRow(String noticeId, String billId, String channel, String recipient, String template, String language,
                            String body, String status, String createdAt, String sentAt) {}

    private final JdbcClient jdbc;
    private final Actors actors;
    private final ReconciliationService reconciliation;
    private final RefundService refunds;
    private final boolean showBodies;

    public FinanceOpsController(JdbcClient jdbc, Actors actors, ReconciliationService reconciliation, RefundService refunds,
                                @Value("${cbrms.notifications.sender:log}") String sender) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.reconciliation = reconciliation;
        this.refunds = refunds;
        this.showBodies = "log".equals(sender);
    }

    // ---------- reconciliation ----------

    @GetMapping("/admin/reconciliation/runs")
    public List<RunRow> runs() {
        finance();
        return jdbc.sql("""
                SELECT b.id, b.business_date, b.status, b.matched_count, b.exception_count, b.missing_count,
                       b.external_total, b.local_total, b.imported_at, u.display_name, b.reviewed_at, b.review_note
                  FROM reconciliation_batch b LEFT JOIN app_user u ON u.id = b.reviewer_id
                 WHERE b.source = 'GEPG'
                 ORDER BY b.business_date DESC, b.imported_at DESC
                 LIMIT 90
                """)
            .query((rs, n) -> new RunRow(rs.getString(1), String.valueOf(rs.getObject(2)), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                    rs.getBigDecimal(7).toPlainString(), rs.getBigDecimal(8).toPlainString(),
                    String.valueOf(rs.getObject(9, OffsetDateTime.class)), rs.getString(10),
                    rs.getObject(11) == null ? null : String.valueOf(rs.getObject(11, OffsetDateTime.class)), rs.getString(12)))
            .list();
    }

    @PostMapping("/admin/reconciliation/runs")
    public ReconciliationService.RunResult run(@Valid @RequestBody RunRequest body) {
        Actor a = finance();
        return reconciliation.run(a.subject(), body.businessDate());
    }

    @GetMapping("/admin/reconciliation/runs/{batchId}/items")
    public List<ItemRow> items(@PathVariable UUID batchId) {
        finance();
        return jdbc.sql("""
                SELECT external_reference, control_number, amount, currency, match_status, case_id
                  FROM reconciliation_item WHERE batch_id = :id ORDER BY match_status DESC, external_reference
                """)
            .param("id", batchId)
            .query((rs, n) -> new ItemRow(rs.getString(1), rs.getString(2), rs.getBigDecimal(3).toPlainString(), rs.getString(4),
                    rs.getString(5), rs.getString(6)))
            .list();
    }

    @PostMapping("/admin/reconciliation/runs/{batchId}/review")
    public Map<String, String> review(@PathVariable UUID batchId, @Valid @RequestBody ReviewRequest body) {
        Actor a = finance();
        reconciliation.review(a.userId(), a.subject(), batchId, body.note());
        return Map.of("status", "REVIEWED");
    }

    // ---------- refunds ----------

    @GetMapping("/admin/refunds")
    public List<RefundRow> refundList(@RequestParam(defaultValue = "") String status) {
        finance();
        return jdbc.sql("""
                SELECT f.id, f.status, f.requested_amount, f.reason, cb.bill_id, b.source_reference, p.display_name, u.display_name,
                       f.execution_ref, f.failure_reason, f.created_at
                  FROM refund_case f
                  LEFT JOIN credit_balance cb ON cb.id = f.credit_balance_id
                  LEFT JOIN bill b ON b.id = cb.bill_id
                  LEFT JOIN payer p ON p.id = b.payer_id
                  LEFT JOIN app_user u ON u.id = f.requested_by
                 WHERE (:status = '' OR f.status = :status)
                 ORDER BY f.created_at DESC LIMIT 200
                """)
            .param("status", status)
            .query((rs, n) -> new RefundRow(rs.getString(1), rs.getString(2), rs.getBigDecimal(3).toPlainString(), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                    String.valueOf(rs.getObject(11, OffsetDateTime.class))))
            .list();
    }

    @PostMapping("/admin/refunds")
    public Map<String, String> requestRefund(@Valid @RequestBody RefundRequest body) {
        Actor a = finance();
        return Map.of("refundId", refunds.request(a, body.creditBalanceId(), body.amount(), body.reason()).toString(), "status", "Requested");
    }

    @PostMapping("/refunds/{refundId}/decision")
    public Map<String, String> decideRefund(@PathVariable UUID refundId, @Valid @RequestBody Decision body) {
        refunds.decide(finance(), refundId, body.decision(), body.reason());
        return Map.of("status", "APPROVED".equals(body.decision()) ? "Approved" : "Rejected");
    }

    @PostMapping("/admin/refunds/{refundId}/execution")
    public Map<String, String> execution(@PathVariable UUID refundId, @Valid @RequestBody ExecutionRequest body) {
        refunds.recordExecution(finance(), refundId, body.executionRef(), body.failureReason());
        return Map.of("status", body.executionRef() != null && !body.executionRef().isBlank() ? "Executed" : "ExecutionFailed");
    }

    // ---------- client notices ----------

    @GetMapping("/admin/notices")
    public List<NoticeRow> notices(@RequestParam(defaultValue = "") String billId) {
        finance();
        UUID bill = billId.isBlank() ? null : UUID.fromString(billId);
        return jdbc.sql("""
                SELECT id, bill_id, channel, recipient, template, language, body, status, created_at, sent_at
                  FROM client_notification
                 WHERE (CAST(:bill AS uuid) IS NULL OR bill_id = CAST(:bill AS uuid))
                 ORDER BY created_at DESC LIMIT 100
                """)
            .param("bill", bill == null ? null : bill.toString())
            .query((rs, n) -> {
                String template = rs.getString(5);
                String body = rs.getString(7);
                // One-time codes are visible only where notices are not really sent (development)
                if ("LOOKUP_CODE".equals(template) && !showBodies) {
                    body = body.replaceAll("\\d{6}", "******");
                }
                String to = rs.getString(4);
                String masked = to.contains("@") ? to.replaceAll("(^.).*(@.*$)", "$1***$2") : "****" + to.substring(Math.max(0, to.length() - 4));
                return new NoticeRow(rs.getString(1), rs.getString(2), rs.getString(3), masked, template, rs.getString(6), body,
                        rs.getString(8), String.valueOf(rs.getObject(9, OffsetDateTime.class)),
                        rs.getObject(10) == null ? null : String.valueOf(rs.getObject(10, OffsetDateTime.class)));
            })
            .list();
    }

    private Actor finance() {
        Actor a = actors.current();
        if (!a.finance()) {
            throw ApiException.forbidden("CBR-AUT-006", "Finance console access requires a Finance role");
        }
        return a;
    }
}
