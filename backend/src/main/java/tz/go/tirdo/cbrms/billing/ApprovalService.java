package tz.go.tirdo.cbrms.billing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.cbrms.audit.AuditService;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.events.EventSubject;
import tz.go.tirdo.cbrms.events.OutboxService;
import tz.go.tirdo.cbrms.identity.Actor;

/** Maker-checker approval of bills (FR07, BR08, AT06). Enforced here and by the approval_maker_checker constraint. */
@Service
public class ApprovalService {
    private final JdbcClient jdbc;
    private final BillQueries bills;
    private final OutboxService outbox;
    private final AuditService audit;

    public ApprovalService(JdbcClient jdbc, BillQueries bills, OutboxService outbox, AuditService audit) {
        this.jdbc = jdbc;
        this.bills = bills;
        this.outbox = outbox;
        this.audit = audit;
    }

    @Transactional
    public void decide(Actor approver, UUID billId, ApprovalRequest request) {
        EventSubject subject = bills.lockSubject(billId).orElseThrow(() -> ApiException.notFound("Bill not found"));
        String state = jdbc.sql("SELECT preparation_state FROM bill WHERE id = :id").param("id", billId).query(String.class).single();
        if (!"AwaitingApproval".equals(state)) {
            throw ApiException.conflict("CBR-STA-001", "Bill is " + state + "; only AwaitingApproval bills can be decided");
        }
        UUID maker = jdbc.sql("SELECT maker_id FROM approval WHERE subject_type = 'BILL' AND subject_id = :id AND decision IS NULL")
                .param("id", billId)
                .query(UUID.class)
                .single();
        if (maker.equals(approver.userId())) {
            audit.record(approver.subject(), "SELF_APPROVAL_DENIED", "bill:" + billId, null, null);
            throw ApiException.forbidden("CBR-AUT-003", "The maker of a bill cannot approve it");
        }
        boolean approved = "APPROVED".equals(request.decision());
        if (!approved && (request.reason() == null || request.reason().isBlank())) {
            throw ApiException.validation("reason", "a reason is required when rejecting");
        }
        jdbc.sql("""
                UPDATE approval SET approver_id = :approver, decision = :decision, reason = :reason, decided_at = now()
                 WHERE subject_type = 'BILL' AND subject_id = :id AND decision IS NULL
                """)
            .param("approver", approver.userId())
            .param("decision", request.decision())
            .param("reason", request.reason())
            .param("id", billId)
            .update();
        String newState = approved ? "Approved" : "Rejected";
        jdbc.sql("UPDATE bill SET preparation_state = :state, row_version = row_version + 1 WHERE id = :id")
            .param("state", newState)
            .param("id", billId)
            .update();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("preparationState", newState);
        data.put("reason", request.reason());
        outbox.append(subject, approved ? "bill.approved" : "bill.rejected", data);
        audit.record(approver.subject(), approved ? "BILL_APPROVED" : "BILL_REJECTED", "bill:" + billId, request.reason(), null);
    }
}
