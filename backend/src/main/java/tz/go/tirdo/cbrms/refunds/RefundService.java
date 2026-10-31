package tz.go.tirdo.cbrms.refunds;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.cbrms.audit.AuditService;
import tz.go.tirdo.cbrms.cases.CaseService;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;

/**
 * Refunds of excess money (SOP-07, AT10). Request, approval by a different officer, then execution recorded
 * with the actual bank reference. Approval in CBRMS never moves money by itself.
 */
@Service
public class RefundService {
    private final JdbcClient jdbc;
    private final CaseService cases;
    private final AuditService audit;

    public RefundService(JdbcClient jdbc, CaseService cases, AuditService audit) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.audit = audit;
    }

    @Transactional
    public UUID request(Actor maker, UUID creditBalanceId, BigDecimal amount, String reason) {
        record Credit(UUID paymentId, BigDecimal amount, String status, UUID billId) {}
        Credit c = jdbc.sql("SELECT payment_id, amount, status, bill_id FROM credit_balance WHERE id = :id FOR UPDATE")
                .param("id", creditBalanceId)
                .query((rs, n) -> new Credit(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getString(3), rs.getObject(4, UUID.class)))
                .optional()
                .orElseThrow(() -> ApiException.notFound("Credit balance not found"));
        if (!"AVAILABLE".equals(c.status())) {
            throw ApiException.conflict("CBR-STA-001", "This credit is already " + c.status().toLowerCase());
        }
        if (amount == null || amount.signum() <= 0 || amount.compareTo(c.amount()) > 0) {
            throw ApiException.validation("amount", "must be more than zero and no more than the credit of " + c.amount().toPlainString());
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.validation("reason", "a reason is required");
        }
        long open = jdbc.sql("SELECT count(*) FROM refund_case WHERE credit_balance_id = :id AND status IN ('Requested','Reviewed','Approved','ExecutionFailed')")
                .param("id", creditBalanceId).query(Long.class).single();
        if (open > 0) {
            throw ApiException.conflict("CBR-STA-004", "A refund for this credit is already in progress");
        }
        UUID refundId = jdbc.sql("""
                INSERT INTO refund_case (payment_id, credit_balance_id, requested_amount, reason, requested_by)
                VALUES (:payment, :credit, :amount, :reason, :by) RETURNING id
                """)
            .param("payment", c.paymentId()).param("credit", creditBalanceId).param("amount", amount)
            .param("reason", reason.trim()).param("by", maker.userId())
            .query(UUID.class).single();
        jdbc.sql("INSERT INTO approval (subject_type, subject_id, maker_id) VALUES ('REFUND', :id, :maker)")
            .param("id", refundId).param("maker", maker.userId()).update();
        audit.record(maker.subject(), "REFUND_REQUESTED", "bill:" + c.billId(), reason, Map.of("refund", refundId.toString(), "amount", amount));
        return refundId;
    }

    /**
     * Refund of a payment's unallocated money (suspense, or freed by a reversal) to the person who paid it.
     * There is no bill or credit, so the payee and their account are recorded. The amount is reserved at once,
     * so the same money cannot also be moved to a bill while the refund is open.
     */
    @Transactional
    public UUID requestFromPayment(Actor maker, UUID paymentId, BigDecimal amount, String reason, String payeeName,
                                   String payeeAccount, UUID caseId) {
        record Pay(String tx, BigDecimal unallocated) {}
        Pay p = jdbc.sql("""
                SELECT p.external_transaction_id, u.unallocated
                  FROM payment p JOIN v_payment_unallocated u ON u.payment_id = p.id
                 WHERE p.id = :id FOR UPDATE OF p
                """)
            .param("id", paymentId)
            .query((rs, n) -> new Pay(rs.getString(1), rs.getBigDecimal(2)))
            .optional()
            .orElseThrow(() -> ApiException.notFound("Payment not found"));
        List<ApiException.FieldProblem> problems = new ArrayList<>();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(p.unallocated()) > 0) {
            problems.add(new ApiException.FieldProblem("amount", "more than zero and no more than the unallocated "
                    + p.unallocated().toPlainString()));
        }
        if (reason == null || reason.isBlank()) {
            problems.add(new ApiException.FieldProblem("reason", "a reason is required"));
        }
        if (payeeName == null || payeeName.isBlank() || payeeName.length() > 200) {
            problems.add(new ApiException.FieldProblem("payeeName", "who receives the money, as on the bank or mobile account"));
        }
        if (payeeAccount == null || !payeeAccount.trim().matches("[A-Za-z0-9 +/-]{6,40}")) {
            problems.add(new ApiException.FieldProblem("payeeAccount", "the bank account or mobile money number (6 to 40 characters)"));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation("Validation failed", problems);
        }
        long open = jdbc.sql("SELECT count(*) FROM correction_request WHERE payment_id = :p AND status = 'Requested'")
                .param("p", paymentId).query(Long.class).single();
        if (open > 0) {
            throw ApiException.conflict("CBR-COR-003", "A correction for payment " + p.tx() + " is waiting for approval; decide it first");
        }
        UUID refundId = jdbc.sql("""
                INSERT INTO refund_case (payment_id, requested_amount, reason, requested_by, case_id, payee_name, payee_account)
                VALUES (:payment, :amount, :reason, :by, :case, :name, :account) RETURNING id
                """)
            .param("payment", paymentId).param("amount", amount).param("reason", reason.trim()).param("by", maker.userId())
            .param("case", caseId).param("name", payeeName.trim()).param("account", payeeAccount.trim())
            .query(UUID.class).single();
        jdbc.sql("INSERT INTO approval (subject_type, subject_id, maker_id) VALUES ('REFUND', :id, :maker)")
            .param("id", refundId).param("maker", maker.userId()).update();
        if (caseId != null) {
            jdbc.sql("UPDATE exception_case SET status = 'IN_PROGRESS', owner_id = coalesce(owner_id, :me) WHERE id = :c AND status = 'OPEN'")
                .param("me", maker.userId()).param("c", caseId).update();
        }
        audit.record(maker.subject(), "REFUND_REQUESTED", "payment:" + paymentId, reason, Map.of("refund", refundId.toString(), "amount", amount));
        return refundId;
    }

    @Transactional
    public void decide(Actor approver, UUID refundId, String decision, String reason) {
        record Open(String status, UUID maker, UUID billId) {}
        Open r = jdbc.sql("""
                SELECT f.status, a.maker_id, cb.bill_id
                  FROM refund_case f
                  JOIN approval a ON a.subject_type = 'REFUND' AND a.subject_id = f.id AND a.decision IS NULL
                  LEFT JOIN credit_balance cb ON cb.id = f.credit_balance_id
                 WHERE f.id = :id FOR UPDATE OF f
                """)
            .param("id", refundId)
            .query((rs, n) -> new Open(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)))
            .optional()
            .orElseThrow(() -> ApiException.conflict("CBR-STA-001", "No refund decision is pending for this case"));
        if (r.maker().equals(approver.userId())) {
            audit.record(approver.subject(), "SELF_APPROVAL_DENIED", "refund:" + refundId, null, null);
            throw ApiException.forbidden("CBR-AUT-003", "The officer who requested this refund cannot approve it");
        }
        boolean approve = "APPROVED".equals(decision);
        if (!approve && (reason == null || reason.isBlank())) {
            throw ApiException.validation("reason", "a reason is required when rejecting");
        }
        UUID approvalId = jdbc.sql("""
                UPDATE approval SET approver_id = :approver, decision = :decision, reason = :reason, decided_at = now()
                 WHERE subject_type = 'REFUND' AND subject_id = :id AND decision IS NULL
                RETURNING id
                """)
            .param("approver", approver.userId()).param("decision", decision).param("reason", reason).param("id", refundId)
            .query(UUID.class).single();
        jdbc.sql("UPDATE refund_case SET status = :status, approval_id = :approval WHERE id = :id")
            .param("status", approve ? "Approved" : "Rejected").param("approval", approvalId).param("id", refundId).update();
        audit.record(approver.subject(), approve ? "REFUND_APPROVED" : "REFUND_REJECTED", "bill:" + r.billId(), reason, Map.of("refund", refundId.toString()));
    }

    /** Records what the bank actually did. Executed needs the transfer reference (invariant I7). */
    @Transactional
    public void recordExecution(Actor officer, UUID refundId, String executionRef, String failureReason) {
        record Approved(String status, UUID creditId, UUID billId, BigDecimal amount, UUID caseId) {}
        Approved r = jdbc.sql("""
                SELECT f.status, f.credit_balance_id, cb.bill_id, f.requested_amount, f.case_id
                  FROM refund_case f LEFT JOIN credit_balance cb ON cb.id = f.credit_balance_id
                 WHERE f.id = :id FOR UPDATE OF f
                """)
            .param("id", refundId)
            .query((rs, n) -> new Approved(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getBigDecimal(4),
                    rs.getObject(5, UUID.class)))
            .optional()
            .orElseThrow(() -> ApiException.notFound("Refund not found"));
        if (!"Approved".equals(r.status()) && !"ExecutionFailed".equals(r.status())) {
            throw ApiException.conflict("CBR-STA-001", "Only an approved refund can be executed (this one is " + r.status() + ")");
        }
        boolean executed = executionRef != null && !executionRef.isBlank();
        if (!executed && (failureReason == null || failureReason.isBlank())) {
            throw ApiException.validation("executionRef", "give the bank transfer reference, or a failure reason");
        }
        if (executed) {
            jdbc.sql("UPDATE refund_case SET status = 'Executed', execution_ref = :ref, executed_at = now(), failure_reason = NULL WHERE id = :id")
                .param("ref", executionRef.trim()).param("id", refundId).update();
            if (r.creditId() != null) {
                jdbc.sql("UPDATE credit_balance SET status = 'REFUNDED' WHERE id = :id").param("id", r.creditId()).update();
            }
            if (r.caseId() != null) {
                // the bank reference is the closure evidence for the unmatched-payment case
                jdbc.sql("""
                        UPDATE exception_case SET status = 'CLOSED', closed_at = now(), evidence_ref = :ref,
                               closure_reason = 'Refunded to the payer (refund ' || :refund || ')'
                         WHERE id = :c AND status <> 'CLOSED'
                        """)
                    .param("ref", executionRef.trim()).param("refund", refundId.toString()).param("c", r.caseId()).update();
            }
            audit.record(officer.subject(), "REFUND_EXECUTED", "bill:" + r.billId(), null, Map.of("refund", refundId.toString(), "reference", executionRef.trim()));
        } else {
            jdbc.sql("UPDATE refund_case SET status = 'ExecutionFailed', failure_reason = :why WHERE id = :id")
                .param("why", failureReason.trim()).param("id", refundId).update();
            cases.open("REFUND_FAILED", r.billId(), refundId.toString(), r.amount(), "TZS", "Refund transfer failed: " + failureReason.trim(), 2);
            audit.record(officer.subject(), "REFUND_FAILED", "bill:" + r.billId(), failureReason, Map.of("refund", refundId.toString()));
        }
    }
}
