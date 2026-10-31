package tz.go.tirdo.cbrms.billing;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tz.go.tirdo.cbrms.audit.AuditService;
import tz.go.tirdo.cbrms.cases.CaseService;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.common.Digests;
import tz.go.tirdo.cbrms.gepg.BillChangeSubmission;
import tz.go.tirdo.cbrms.gepg.GepgGateway;
import tz.go.tirdo.cbrms.gepg.GepgProviders;
import tz.go.tirdo.cbrms.gepg.SubmitResult;
import tz.go.tirdo.cbrms.identity.Actor;

/**
 * Cancellation and expiry change of bills already registered with GePG (FR10, FR33, SOP-03, SOP-04).
 * A request is approved by a second officer; only then is GePG asked. The bill stays CancellationPending or
 * ExpiryChangePending until GePG confirms, and any payment arriving meanwhile is still recorded.
 */
@Service
public class BillChangeService {
    private static final Logger log = LoggerFactory.getLogger(BillChangeService.class);

    public record ChangeView(UUID requestId, UUID billId, String kind, String status) {}

    private record BillState(UUID sourceSystemId, String sourceReference, String registration, String collection, LocalDate dueDate) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final BillQueries bills;
    private final GepgGateway gateway;
    private final GepgProviders providers;
    private final CaseService cases;
    private final AuditService audit;

    public BillChangeService(JdbcClient jdbc, TransactionTemplate tx, BillQueries bills, GepgGateway gateway,
                             GepgProviders providers, CaseService cases, AuditService audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.bills = bills;
        this.gateway = gateway;
        this.providers = providers;
        this.cases = cases;
        this.audit = audit;
    }

    @Transactional
    public ChangeView requestCancellation(Actor actor, UUID billId, String reason) {
        BillState b = lockAndCheckAccess(actor, billId);
        requireReason(reason);
        if (!"Accepted".equals(b.registration())) {
            throw ApiException.conflict("CBR-STA-001", "Only bills with an issued control number can be cancelled through GePG (bill is "
                    + b.registration() + ")");
        }
        if (!"Unpaid".equals(b.collection())) {
            throw ApiException.conflict("CBR-STA-003", "This bill has received money. Use the correction route agreed with Finance (SOP-04), not cancellation");
        }
        return create(actor, billId, "CANCELLATION", reason, null);
    }

    @Transactional
    public ChangeView requestExpiryChange(Actor actor, UUID billId, LocalDate newDueDate, String reason) {
        BillState b = lockAndCheckAccess(actor, billId);
        requireReason(reason);
        if (!"Accepted".equals(b.registration())) {
            throw ApiException.conflict("CBR-STA-001", "Only bills with an issued control number can have their deadline changed (bill is "
                    + b.registration() + ")");
        }
        if (!"Unpaid".equals(b.collection()) && !"PartiallyPaid".equals(b.collection())) {
            throw ApiException.conflict("CBR-STA-001", "This bill is already paid");
        }
        LocalDate today = LocalDate.now(BillService.BUSINESS_ZONE);
        if (newDueDate == null || !newDueDate.isAfter(b.dueDate()) || newDueDate.isAfter(today.plusDays(365))) {
            throw ApiException.validation("newDueDate", "must be later than the current due date " + b.dueDate() + " and within one year");
        }
        OffsetDateTime until = newDueDate.atTime(LocalTime.of(23, 59, 59)).atZone(BillService.BUSINESS_ZONE).toOffsetDateTime();
        return create(actor, billId, "EXPIRY_CHANGE", reason, until);
    }

    /** Approver's decision. On approval, GePG is asked after the decision commits; its answer arrives as a message. */
    public ChangeView decide(Actor approver, UUID requestId, String decision, String reason) {
        boolean approve = "APPROVED".equals(decision);
        BillChangeSubmission submission = tx.execute(status -> {
            record Req(UUID billId, String kind, String status, UUID approvalId, UUID maker, OffsetDateTime newValidUntil, String reason) {}
            Req r = jdbc.sql("""
                    SELECT r.bill_id, r.kind, r.status, r.approval_id, a.maker_id, r.new_valid_until, r.reason
                      FROM bill_change_request r JOIN approval a ON a.id = r.approval_id
                     WHERE r.id = :id FOR UPDATE OF r
                    """)
                .param("id", requestId)
                .query((rs, n) -> new Req(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), rs.getObject(6, OffsetDateTime.class), rs.getString(7)))
                .optional()
                .orElseThrow(() -> ApiException.notFound("Change request not found"));
            if (!"Requested".equals(r.status())) {
                throw ApiException.conflict("CBR-STA-001", "This request is already " + r.status());
            }
            if (r.maker().equals(approver.userId())) {
                audit.record(approver.subject(), "SELF_APPROVAL_DENIED", "change:" + requestId, null, null);
                throw ApiException.forbidden("CBR-AUT-003", "The officer who requested this change cannot approve it");
            }
            if (!approve && (reason == null || reason.isBlank())) {
                throw ApiException.validation("reason", "a reason is required when rejecting");
            }
            bills.lockSubject(r.billId()).orElseThrow();
            jdbc.sql("UPDATE approval SET approver_id = :approver, decision = :decision, reason = :reason, decided_at = now() WHERE id = :id")
                .param("approver", approver.userId())
                .param("decision", decision)
                .param("reason", reason)
                .param("id", r.approvalId())
                .update();
            if (!approve) {
                jdbc.sql("UPDATE bill_change_request SET status = 'Rejected', completed_at = now() WHERE id = :id").param("id", requestId).update();
                audit.record(approver.subject(), r.kind() + "_REJECTED", "bill:" + r.billId(), reason, null);
                return null;
            }
            String registration = jdbc.sql("SELECT registration_state FROM bill WHERE id = :id").param("id", r.billId()).query(String.class).single();
            if (!"Accepted".equals(registration)) {
                throw ApiException.conflict("CBR-STA-001", "The bill changed since the request was made (now " + registration + ")");
            }
            String pending = "CANCELLATION".equals(r.kind()) ? "CancellationPending" : "ExpiryChangePending";
            jdbc.sql("UPDATE bill SET registration_state = :state, row_version = row_version + 1 WHERE id = :id")
                .param("state", pending).param("id", r.billId()).update();
            record Ext(UUID id, String gepgBillId, String controlNumber) {}
            Ext ext = jdbc.sql("SELECT id, gepg_bill_id, control_number FROM external_bill WHERE bill_id = :id")
                .param("id", r.billId())
                .query((rs, n) -> new Ext(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .single();
            BillChangeSubmission s = new BillChangeSubmission(requestId, r.kind(), ext.gepgBillId(), ext.controlNumber(),
                    providers.current().spCode(), r.reason(), r.newValidUntil());
            jdbc.sql("INSERT INTO submission_attempt (external_bill_id, kind, request_digest, state) VALUES (:ext, :kind, :digest, 'SENT')")
                .param("ext", ext.id())
                .param("kind", "CANCELLATION".equals(r.kind()) ? "CANCEL" : "EXPIRY_CHANGE")
                .param("digest", Digests.sha256(requestId + ":" + r.kind() + ":" + r.newValidUntil()))
                .update();
            jdbc.sql("UPDATE bill_change_request SET status = 'SentToGepg' WHERE id = :id").param("id", requestId).update();
            audit.record(approver.subject(), r.kind() + "_APPROVED", "bill:" + r.billId(), reason, null);
            return s;
        });
        if (submission != null) {
            send(submission);
        }
        return view(requestId);
    }

    private void send(BillChangeSubmission s) {
        String attemptKind = "CANCELLATION".equals(s.kind()) ? "CANCEL" : "EXPIRY_CHANGE";
        try {
            SubmitResult result = gateway.requestChange(s);
            tx.executeWithoutResult(status -> {
                markAttempt(s, attemptKind, result.acknowledged() ? "ACKNOWLEDGED" : "REJECTED", result.responseCode());
                if (!result.acknowledged()) {
                    UUID billId = billOf(s.changeRequestId());
                    jdbc.sql("UPDATE bill SET registration_state = 'Accepted' WHERE id = :id").param("id", billId).update();
                    jdbc.sql("UPDATE bill_change_request SET status = 'RefusedByGepg', gepg_code = :code, completed_at = now() WHERE id = :id")
                        .param("code", result.responseCode()).param("id", s.changeRequestId()).update();
                }
            });
        } catch (Exception e) {
            log.warn("GePG change outcome unknown for request {}: {}", s.changeRequestId(), e.getMessage());
            tx.executeWithoutResult(status -> {
                markAttempt(s, attemptKind, "UNKNOWN", null);
                jdbc.sql("UPDATE bill_change_request SET status = 'OutcomeUnknown' WHERE id = :id").param("id", s.changeRequestId()).update();
                cases.openOnceForBill("CHANGE_OUTCOME_UNKNOWN", billOf(s.changeRequestId()),
                        s.kind() + " sent to GePG with no answer; confirm by enquiry before acting again");
            });
        }
    }

    private void markAttempt(BillChangeSubmission s, String kind, String state, String code) {
        jdbc.sql("""
                UPDATE submission_attempt SET state = :state, response_code = :code
                 WHERE id = (SELECT a.id FROM submission_attempt a JOIN external_bill e ON e.id = a.external_bill_id
                              WHERE e.gepg_bill_id = :g AND a.kind = :kind ORDER BY a.sent_at DESC LIMIT 1)
                """)
            .param("state", state).param("code", code).param("g", s.gepgBillId()).param("kind", kind).update();
    }

    private UUID billOf(UUID requestId) {
        return jdbc.sql("SELECT bill_id FROM bill_change_request WHERE id = :id").param("id", requestId).query(UUID.class).single();
    }

    public ChangeView view(UUID requestId) {
        return jdbc.sql("SELECT id, bill_id, kind, status FROM bill_change_request WHERE id = :id")
            .param("id", requestId)
            .query((rs, n) -> new ChangeView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)))
            .optional()
            .orElseThrow(() -> ApiException.notFound("Change request not found"));
    }

    private ChangeView create(Actor actor, UUID billId, String kind, String reason, OffsetDateTime newValidUntil) {
        Optional<UUID> id = jdbc.sql("""
                INSERT INTO bill_change_request (bill_id, kind, reason, new_valid_until, requested_by)
                VALUES (:bill, :kind, :reason, :until, :by)
                ON CONFLICT DO NOTHING
                RETURNING id
                """)
            .param("bill", billId).param("kind", kind).param("reason", reason.trim()).param("until", newValidUntil).param("by", actor.userId())
            .query(UUID.class)
            .optional();
        if (id.isEmpty()) {
            throw ApiException.conflict("CBR-STA-004", "This bill already has a change request in progress");
        }
        UUID approvalId = jdbc.sql("INSERT INTO approval (subject_type, subject_id, maker_id) VALUES (:type, :subject, :maker) RETURNING id")
                .param("type", kind).param("subject", id.get()).param("maker", actor.userId())
                .query(UUID.class)
                .single();
        jdbc.sql("UPDATE bill_change_request SET approval_id = :a WHERE id = :id").param("a", approvalId).param("id", id.get()).update();
        audit.record(actor.subject(), kind + "_REQUESTED", "bill:" + billId, reason,
                newValidUntil == null ? null : Map.of("newValidUntil", newValidUntil.toString()));
        return new ChangeView(id.get(), billId, kind, "Requested");
    }

    private BillState lockAndCheckAccess(Actor actor, UUID billId) {
        bills.lockSubject(billId).orElseThrow(() -> ApiException.notFound("Bill not found"));
        BillState b = jdbc.sql("SELECT source_system_id, source_reference, registration_state, collection_state, due_date FROM bill WHERE id = :id")
                .param("id", billId)
                .query((rs, n) -> new BillState(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, LocalDate.class)))
                .single();
        if (!actor.finance() && !b.sourceSystemId().equals(actor.sourceSystemId())) {
            throw ApiException.notFound("Bill not found");
        }
        return b;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw ApiException.validation("reason", "a reason of up to 500 characters is required");
        }
    }
}
