package tz.go.tirdo.cbrms.payments;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.cbrms.audit.AuditService;
import tz.go.tirdo.cbrms.billing.BillQueries;
import tz.go.tirdo.cbrms.cases.CaseService;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.domain.AllocationRules;
import tz.go.tirdo.cbrms.domain.PaymentMode;
import tz.go.tirdo.cbrms.events.EventSubject;
import tz.go.tirdo.cbrms.events.OutboxService;
import tz.go.tirdo.cbrms.identity.Actor;

/**
 * Financial corrections (FR18, FR21, AT14), each approved by a second officer.
 * ALLOCATE puts a payment's unallocated money (suspense, or money freed by a reversal) on a bill.
 * REVERSE appends reversal rows for a payment's allocations on a bill; the money becomes unallocated again.
 * Ledger rows are only ever appended; every applied correction records a financial_adjustment.
 */
@Service
public class CorrectionService {

    public enum Kind { ALLOCATE, REVERSE }

    public record UnallocatedPayment(String paymentId, String transactionId, String controlNumber, String channel, String amount,
                                     String unallocated, String receivedAt, String caseId, String openCorrectionId) {}

    public record CorrectionView(String correctionId, String kind, String status, String paymentId, String transactionId,
                                 String billId, String sourceSystem, String sourceReference, String payerName, String controlNumber,
                                 String amount, String reason, String caseId, String requestedBy, String createdAt, String appliedAt) {}

    private record Payment(UUID id, String tx, String currency, BigDecimal amount, BigDecimal unallocated) {}

    private record Bill(BigDecimal total, String currency, PaymentMode mode, UUID payerId, String registration, String preparation,
                        String release) {}

    private final JdbcClient jdbc;
    private final BillQueries bills;
    private final OutboxService outbox;
    private final CaseService cases;
    private final AuditService audit;

    public CorrectionService(JdbcClient jdbc, BillQueries bills, OutboxService outbox, CaseService cases, AuditService audit) {
        this.jdbc = jdbc;
        this.bills = bills;
        this.outbox = outbox;
        this.cases = cases;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- requests

    @Transactional
    public CorrectionView request(Actor maker, Kind kind, UUID paymentId, UUID billId, String reason, UUID caseId) {
        if (!maker.finance()) {
            throw ApiException.forbidden("CBR-AUT-006", "Corrections are requested by Finance");
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw ApiException.validation("reason", "say why, in up to 500 characters");
        }
        Payment p = payment(paymentId, false);
        BigDecimal amount;
        if (kind == Kind.ALLOCATE) {
            if (p.unallocated().signum() <= 0) {
                throw ApiException.conflict("CBR-COR-001", "Payment " + p.tx() + " has no unallocated money to move");
            }
            Bill b = bill(billId);
            checkTarget(b, p);
            amount = p.unallocated();
        } else {
            amount = reversible(paymentId, billId);
            if (amount.signum() <= 0) {
                throw ApiException.conflict("CBR-COR-002", "Payment " + p.tx() + " has nothing applied to this bill to reverse");
            }
        }
        long open = jdbc.sql("SELECT count(*) FROM correction_request WHERE payment_id = :p AND status = 'Requested'")
                .param("p", paymentId).query(Long.class).single();
        if (open > 0) {
            throw ApiException.conflict("CBR-COR-003", "A correction for payment " + p.tx() + " is already waiting for approval");
        }
        UUID id = jdbc.sql("""
                INSERT INTO correction_request (kind, payment_id, bill_id, amount, reason, case_id, requested_by)
                VALUES (:kind, :p, :b, :amount, :reason, :case, :by) RETURNING id
                """)
            .param("kind", kind.name()).param("p", paymentId).param("b", billId).param("amount", amount)
            .param("reason", reason.trim()).param("case", caseId).param("by", maker.userId())
            .query(UUID.class).single();
        UUID approval = jdbc.sql("INSERT INTO approval (subject_type, subject_id, maker_id) VALUES ('ADJUSTMENT', :id, :maker) RETURNING id")
                .param("id", id).param("maker", maker.userId()).query(UUID.class).single();
        jdbc.sql("UPDATE correction_request SET approval_id = :a WHERE id = :id").param("a", approval).param("id", id).update();
        if (caseId != null) {
            jdbc.sql("UPDATE exception_case SET status = 'IN_PROGRESS', owner_id = coalesce(owner_id, :me) WHERE id = :c AND status = 'OPEN'")
                .param("me", maker.userId()).param("c", caseId).update();
        }
        audit.record(maker.subject(), "CORRECTION_REQUESTED", "payment:" + paymentId, reason,
                Map.of("kind", kind.name(), "bill", billId.toString(), "amount", amount));
        return view(id);
    }

    @Transactional
    public CorrectionView decide(Actor approver, UUID correctionId, String decision, String reason) {
        if (!approver.finance()) {
            throw ApiException.forbidden("CBR-AUT-006", "Only Finance can approve corrections");
        }
        record Open(String kind, String status, UUID approvalId, UUID makerId, UUID paymentId, UUID billId, UUID caseId) {}
        Open c = jdbc.sql("""
                SELECT kind, status, approval_id, requested_by, payment_id, bill_id, case_id FROM correction_request WHERE id = :id FOR UPDATE
                """)
            .param("id", correctionId)
            .query((rs, n) -> new Open(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                    rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class)))
            .optional()
            .orElseThrow(() -> ApiException.notFound("Correction not found"));
        if (!"Requested".equals(c.status())) {
            throw ApiException.conflict("CBR-STA-001", "This correction is already " + c.status().toLowerCase());
        }
        if (approver.userId().equals(c.makerId())) {
            throw ApiException.forbidden("CBR-AUT-003", "The officer who requested a correction cannot approve it");
        }
        boolean approve = "APPROVED".equals(decision);
        if (!approve && (reason == null || reason.isBlank())) {
            throw ApiException.validation("reason", "a reason is required to reject");
        }
        jdbc.sql("UPDATE approval SET approver_id = :by, decision = :d, reason = :r, decided_at = now() WHERE id = :id")
            .param("by", approver.userId()).param("d", approve ? "APPROVED" : "REJECTED").param("r", reason).param("id", c.approvalId())
            .update();
        if (approve) {
            BigDecimal applied = Kind.valueOf(c.kind()) == Kind.ALLOCATE
                    ? allocate(c.paymentId(), c.billId(), correctionId)
                    : reverse(c.paymentId(), c.billId(), correctionId);
            UUID adjustment = jdbc.sql("""
                    INSERT INTO financial_adjustment (original_record_type, original_record_id, type, amount, approval_id, external_ref)
                    VALUES ('payment', :p, :type, :amount, :approval, :ref) RETURNING id
                    """)
                .param("p", c.paymentId()).param("type", Kind.valueOf(c.kind()) == Kind.ALLOCATE ? "REALLOCATION" : "REVERSAL")
                .param("amount", applied).param("approval", c.approvalId()).param("ref", "correction:" + correctionId)
                .query(UUID.class).single();
            jdbc.sql("UPDATE correction_request SET status = 'Applied', applied_at = now(), adjustment_id = :a WHERE id = :id")
                .param("a", adjustment).param("id", correctionId).update();
            if (c.caseId() != null && Kind.valueOf(c.kind()) == Kind.ALLOCATE) {
                jdbc.sql("""
                        UPDATE exception_case SET status = 'CLOSED', closed_at = now(), evidence_ref = :ref,
                               closure_reason = 'Resolved by approved correction: payment moved to bill'
                         WHERE id = :c AND status <> 'CLOSED'
                        """)
                    .param("ref", "correction:" + correctionId).param("c", c.caseId()).update();
            }
        } else {
            jdbc.sql("UPDATE correction_request SET status = 'Rejected' WHERE id = :id").param("id", correctionId).update();
        }
        audit.record(approver.subject(), approve ? "CORRECTION_APPLIED" : "CORRECTION_REJECTED", "correction:" + correctionId, reason, null);
        return view(correctionId);
    }

    // ---------------------------------------------------------------- applying

    /** Applies the payment's unallocated money to the bill under the bill's payment mode; excess becomes credit. */
    private BigDecimal allocate(UUID paymentId, UUID billId, UUID correctionId) {
        Payment p = payment(paymentId, true);
        if (p.unallocated().signum() <= 0) {
            throw ApiException.conflict("CBR-COR-001", "Payment " + p.tx() + " no longer has unallocated money");
        }
        EventSubject subject = bills.lockSubject(billId).orElseThrow(() -> ApiException.notFound("Bill not found"));
        Bill b = bill(billId);
        checkTarget(b, p);
        BigDecimal net = netAllocated(billId);
        AllocationRules.Decision d = AllocationRules.decide(b.mode(), b.total(), net, p.unallocated());
        if (d.allocate().signum() > 0) {
            jdbc.sql("INSERT INTO payment_allocation (payment_id, bill_id, kind, amount) VALUES (:p, :b, 'ALLOCATION', :a)")
                .param("p", paymentId).param("b", billId).param("a", d.allocate()).update();
        }
        if (d.excess().signum() > 0) {
            jdbc.sql("INSERT INTO credit_balance (payer_id, payment_id, bill_id, amount) VALUES (:payer, :p, :b, :a)")
                .param("payer", b.payerId()).param("p", paymentId).param("b", billId).param("a", d.excess()).update();
        }
        if (d.exceptionRequired()) {
            cases.open("AMOUNT_MISMATCH", billId, p.tx(), p.unallocated(), p.currency(), d.exceptionReason() + " (moved by correction)", 1);
        }
        String state = d.newState().name();
        String release = ("Paid".equals(state) || "Overpaid".equals(state)) && "Pending".equals(b.release()) ? "Eligible" : b.release();
        updateBill(billId, state, release);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentReference", p.tx());
        data.put("amount", p.unallocated());
        data.put("currency", p.currency());
        data.put("allocated", d.allocate());
        data.put("excess", d.excess());
        data.put("outstanding", b.total().subtract(net).subtract(d.allocate()).max(BigDecimal.ZERO));
        data.put("collectionState", state);
        data.put("releaseState", release);
        data.put("correction", "correction:" + correctionId);
        outbox.append(subject, "payment.received", data);
        return p.unallocated();
    }

    /** Appends a REVERSAL for each of the payment's unreversed allocations on the bill. The originals stay. */
    private BigDecimal reverse(UUID paymentId, UUID billId, UUID correctionId) {
        Payment p = payment(paymentId, true);
        EventSubject subject = bills.lockSubject(billId).orElseThrow(() -> ApiException.notFound("Bill not found"));
        record Alloc(UUID id, BigDecimal amount) {}
        List<Alloc> open = jdbc.sql("""
                SELECT a.id, a.amount FROM payment_allocation a
                 WHERE a.payment_id = :p AND a.bill_id = :b AND a.kind = 'ALLOCATION'
                   AND NOT EXISTS (SELECT 1 FROM payment_allocation r WHERE r.reverses_allocation_id = a.id)
                """)
            .param("p", paymentId).param("b", billId)
            .query((rs, n) -> new Alloc(rs.getObject(1, UUID.class), rs.getBigDecimal(2)))
            .list();
        if (open.isEmpty()) {
            throw ApiException.conflict("CBR-COR-002", "Payment " + p.tx() + " has nothing applied to this bill to reverse");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Alloc a : open) {
            jdbc.sql("""
                    INSERT INTO payment_allocation (payment_id, bill_id, kind, amount, reverses_allocation_id)
                    VALUES (:p, :b, 'REVERSAL', :amount, :orig)
                    """)
                .param("p", paymentId).param("b", billId).param("amount", a.amount().negate()).param("orig", a.id()).update();
            total = total.add(a.amount());
        }
        Bill b = bill(billId);
        BigDecimal net = netAllocated(billId);
        boolean credit = jdbc.sql("SELECT count(*) FROM credit_balance WHERE bill_id = :b").param("b", billId).query(Long.class).single() > 0;
        String state = net.signum() == 0 ? "Unpaid" : net.compareTo(b.total()) < 0 ? "PartiallyPaid" : credit ? "Overpaid" : "Paid";
        boolean wasReleased = "Released".equals(b.release());
        boolean stillPaid = "Paid".equals(state) || "Overpaid".equals(state);
        String release = wasReleased ? "Released" : stillPaid ? b.release() : "Pending";
        if (wasReleased && !stillPaid) {
            // Invariant I4: a released, unpaid bill needs an approved exception; the approved correction is that authority.
            // Set in the same statement as the state change so the CHECK sees both together.
            jdbc.sql("""
                    UPDATE bill SET collection_state = :c, release_exception_ref = coalesce(release_exception_ref, :ref),
                           row_version = row_version + 1
                     WHERE id = :id
                    """)
                .param("c", state).param("ref", "correction:" + correctionId).param("id", billId).update();
        } else {
            updateBill(billId, state, release);
        }

        if (wasReleased && !stillPaid) {
            // The service has already been delivered: the platform and Finance must review (AT14)
            cases.open("REVERSAL_AFTER_RELEASE", billId, p.tx(), total, p.currency(),
                    "Payment " + p.tx() + " reversed after the service was released; the platform must review", 1);
        }
        // The money is unallocated again; keep it visible until it is moved or refunded
        cases.open("UNMATCHED_PAYMENT", null, p.tx(), total, p.currency(),
                "Reversed from bill " + subject.sourceReference() + "; move it to the right bill or refund it", 2);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentReference", p.tx());
        data.put("reversed", total);
        data.put("currency", p.currency());
        data.put("outstanding", b.total().subtract(net).max(BigDecimal.ZERO));
        data.put("collectionState", state);
        data.put("releaseState", release);
        data.put("reviewRequired", wasReleased && !stillPaid);
        data.put("correction", "correction:" + correctionId);
        outbox.append(subject, "payment.reversed", data);
        return total;
    }

    private void updateBill(UUID billId, String collection, String release) {
        jdbc.sql("""
                UPDATE bill SET collection_state = :c, release_state = :r,
                       reconciliation_state = CASE WHEN reconciliation_state = 'NotDue' THEN 'Pending' ELSE reconciliation_state END,
                       row_version = row_version + 1
                 WHERE id = :id
                """)
            .param("c", collection).param("r", release).param("id", billId).update();
    }

    // ---------------------------------------------------------------- reading

    public List<UnallocatedPayment> unallocated() {
        return jdbc.sql("""
                SELECT p.id, p.external_transaction_id, p.control_number, p.channel, p.amount, u.unallocated, p.received_at,
                       (SELECT c.id FROM exception_case c WHERE c.type = 'UNMATCHED_PAYMENT' AND c.reference = p.external_transaction_id
                                                            AND c.status <> 'CLOSED' ORDER BY c.opened_at DESC LIMIT 1),
                       (SELECT r.id FROM correction_request r WHERE r.payment_id = p.id AND r.status = 'Requested')
                  FROM payment p
                  CROSS JOIN LATERAL (SELECT p.amount
                           - coalesce((SELECT sum(a.amount) FROM payment_allocation a WHERE a.payment_id = p.id), 0)
                           - coalesce((SELECT sum(c.amount) FROM credit_balance c WHERE c.payment_id = p.id), 0) AS unallocated) u
                 WHERE u.unallocated > 0
                 ORDER BY p.received_at
                 LIMIT 200
                """)
            .query((rs, n) -> new UnallocatedPayment(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getBigDecimal(5).toPlainString(), rs.getBigDecimal(6).toPlainString(),
                    String.valueOf(rs.getObject(7, java.time.OffsetDateTime.class)), rs.getString(8), rs.getString(9)))
            .list();
    }

    public List<CorrectionView> list(String status) {
        return jdbc.sql("SELECT id FROM correction_request WHERE (:s = '' OR status = :s) ORDER BY created_at DESC LIMIT 200")
                .param("s", status == null ? "" : status).query(UUID.class).list().stream().map(this::view).toList();
    }

    public CorrectionView view(UUID id) {
        return jdbc.sql("""
                SELECT r.id, r.kind, r.status, r.payment_id, p.external_transaction_id, r.bill_id, s.code, b.source_reference,
                       py.display_name, e.control_number, r.amount, r.reason, r.case_id, u.display_name, r.created_at, r.applied_at
                  FROM correction_request r
                  JOIN payment p ON p.id = r.payment_id
                  JOIN bill b ON b.id = r.bill_id
                  JOIN source_system s ON s.id = b.source_system_id
                  JOIN payer py ON py.id = b.payer_id
                  LEFT JOIN external_bill e ON e.bill_id = b.id
                  JOIN app_user u ON u.id = r.requested_by
                 WHERE r.id = :id
                """)
            .param("id", id)
            .query((rs, n) -> new CorrectionView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                    rs.getBigDecimal(11).toPlainString(), rs.getString(12), rs.getString(13), rs.getString(14),
                    String.valueOf(rs.getObject(15, java.time.OffsetDateTime.class)),
                    rs.getObject(16) == null ? null : String.valueOf(rs.getObject(16, java.time.OffsetDateTime.class))))
            .optional()
            .orElseThrow(() -> ApiException.notFound("Correction not found"));
    }

    public Optional<UUID> paymentByTransaction(String tx) {
        return jdbc.sql("SELECT id FROM payment WHERE external_transaction_id = :tx").param("tx", tx).query(UUID.class).optional();
    }

    // ---------------------------------------------------------------- helpers

    private Payment payment(UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT p.id, p.external_transaction_id, p.currency, p.amount,
                       p.amount - coalesce((SELECT sum(a.amount) FROM payment_allocation a WHERE a.payment_id = p.id), 0)
                                - coalesce((SELECT sum(c.amount) FROM credit_balance c WHERE c.payment_id = p.id), 0)
                  FROM payment p WHERE p.id = :id
                """ + (lock ? " FOR UPDATE OF p" : ""))
            .param("id", id)
            .query((rs, n) -> new Payment(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                    rs.getBigDecimal(5)))
            .optional()
            .orElseThrow(() -> ApiException.notFound("Payment not found"));
    }

    private Bill bill(UUID id) {
        return jdbc.sql("""
                SELECT total, currency, payment_mode, payer_id, registration_state, preparation_state, release_state FROM bill WHERE id = :id
                """)
            .param("id", id)
            .query((rs, n) -> new Bill(rs.getBigDecimal(1), rs.getString(2), PaymentMode.valueOf(rs.getString(3)),
                    rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getString(7)))
            .optional()
            .orElseThrow(() -> ApiException.notFound("Bill not found"));
    }

    private static void checkTarget(Bill b, Payment p) {
        if (!List.of("Accepted", "ExpiryChangePending").contains(b.registration())
                || List.of("Rejected", "Superseded").contains(b.preparation())) {
            throw new ApiException(422, "CBR-COR-004", "Money can only be moved to an issued, payable bill");
        }
        if (!b.currency().equals(p.currency())) {
            throw new ApiException(422, "CBR-COR-005", "The payment is in " + p.currency() + " but the bill is in " + b.currency());
        }
    }

    private BigDecimal netAllocated(UUID billId) {
        return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM payment_allocation WHERE bill_id = :b").param("b", billId)
                .query(BigDecimal.class).single();
    }

    private BigDecimal reversible(UUID paymentId, UUID billId) {
        return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM payment_allocation WHERE payment_id = :p AND bill_id = :b")
                .param("p", paymentId).param("b", billId).query(BigDecimal.class).single();
    }
}
