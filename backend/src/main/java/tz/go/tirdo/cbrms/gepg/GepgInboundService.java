package tz.go.tirdo.cbrms.gepg;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.cbrms.audit.AuditService;
import tz.go.tirdo.cbrms.billing.BillQueries;
import tz.go.tirdo.cbrms.cases.CaseService;
import tz.go.tirdo.cbrms.common.Correlation;
import tz.go.tirdo.cbrms.common.Digests;
import tz.go.tirdo.cbrms.events.EventSubject;
import tz.go.tirdo.cbrms.events.OutboxService;
import tz.go.tirdo.cbrms.notifications.NotificationService;
import tz.go.tirdo.cbrms.payments.PaymentService;

/**
 * Entry point for every inbound GePG message (Blueprint section 5).
 * Verify, record once in the inbox, then process, all in one transaction that commits before GePG is acknowledged.
 */
@Service
public class GepgInboundService {
    private static final Logger log = LoggerFactory.getLogger(GepgInboundService.class);

    private final JdbcClient jdbc;
    private final MessageVerifier verifier;
    private final GepgProviders providers;
    private final PaymentService payments;
    private final BillQueries bills;
    private final OutboxService outbox;
    private final CaseService cases;
    private final AuditService audit;
    private final NotificationService notices;

    public GepgInboundService(JdbcClient jdbc, MessageVerifier verifier, GepgProviders providers, PaymentService payments,
                              BillQueries bills, OutboxService outbox, CaseService cases, AuditService audit,
                              NotificationService notices) {
        this.jdbc = jdbc;
        this.verifier = verifier;
        this.providers = providers;
        this.payments = payments;
        this.bills = bills;
        this.outbox = outbox;
        this.cases = cases;
        this.audit = audit;
        this.notices = notices;
    }

    @Transactional
    public InboundResult receivePayment(String raw, String signature, PaymentMessage message) {
        UUID providerId = providers.current().id();
        Optional<UUID> inboxId = recordInbox(providerId, "PAYMENT", message.messageId(), raw, signature);
        if (inboxId == null) {
            return InboundResult.REJECTED_SIGNATURE;
        }
        if (inboxId.isEmpty()) {
            return InboundResult.DUPLICATE;
        }
        return payments.post(providerId, inboxId.get(), message);
    }

    @Transactional
    public InboundResult receiveControlNumber(String raw, String signature, ControlNumberMessage message) {
        UUID providerId = providers.current().id();
        Optional<UUID> inboxId = recordInbox(providerId, "CONTROL_NUMBER", message.messageId(), raw, signature);
        if (inboxId == null) {
            return InboundResult.REJECTED_SIGNATURE;
        }
        if (inboxId.isEmpty()) {
            return InboundResult.DUPLICATE;
        }
        Optional<UUID> billId = jdbc.sql("SELECT bill_id FROM external_bill WHERE provider_id = :p AND gepg_bill_id = :g")
                .param("p", providerId)
                .param("g", message.gepgBillId())
                .query(UUID.class)
                .optional();
        if (billId.isEmpty()) {
            audit.record("gepg", "CONTROL_NUMBER_UNKNOWN_BILL", "gepg-bill:" + message.gepgBillId(), null, null);
            return InboundResult.IGNORED;
        }
        EventSubject subject = bills.lockSubject(billId.get()).orElseThrow();
        String state = jdbc.sql("SELECT registration_state FROM bill WHERE id = :id").param("id", billId.get()).query(String.class).single();
        if (!state.equals("Submitted") && !state.equals("AckUnknown") && !state.equals("AwaitingControlNumber")) {
            audit.record("gepg", "CONTROL_NUMBER_RESULT_IGNORED", "bill:" + billId.get(), "bill is " + state, null);
            return InboundResult.IGNORED;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (message.accepted()) {
            jdbc.sql("""
                    UPDATE external_bill
                       SET control_number = :cn, control_number_at = now(), acknowledged_at = coalesce(acknowledged_at, now())
                     WHERE bill_id = :id
                    """)
                .param("cn", message.controlNumber())
                .param("id", billId.get())
                .update();
            jdbc.sql("UPDATE bill SET registration_state = 'Accepted', issued_at = now(), row_version = row_version + 1 WHERE id = :id")
                .param("id", billId.get())
                .update();
            data.put("controlNumber", message.controlNumber());
            data.put("registrationState", "Accepted");
            outbox.append(subject, "bill.control-number.issued", data);
            audit.record("gepg", "CONTROL_NUMBER_ISSUED", "bill:" + billId.get(), null, Map.of("controlNumber", message.controlNumber()));
            Map<String, Object> bill = jdbc.sql("SELECT description, total, due_date FROM bill WHERE id = :id").param("id", billId.get()).query().singleRow();
            notices.enqueueForBill(billId.get(), "CONTROL_NUMBER_ISSUED", Map.of(
                    "cn", NotificationService.controlNumber(message.controlNumber()),
                    "description", String.valueOf(bill.get("description")),
                    "amount", NotificationService.amount((java.math.BigDecimal) bill.get("total")),
                    "due", NotificationService.date(((java.sql.Date) bill.get("due_date")).toLocalDate()),
                    "ref", subject.sourceReference()));
        } else {
            jdbc.sql("UPDATE bill SET registration_state = 'Rejected', row_version = row_version + 1 WHERE id = :id")
                .param("id", billId.get())
                .update();
            data.put("registrationState", "Rejected");
            data.put("responseCode", message.responseCode());
            outbox.append(subject, "bill.registration.rejected", data);
            audit.record("gepg", "BILL_REJECTED_BY_GEPG", "bill:" + billId.get(), message.responseCode(), null);
        }
        return InboundResult.ACCEPTED;
    }

    /** GePG's answer to a cancellation or expiry change (FR10, FR33). */
    @Transactional
    public InboundResult receiveChangeResult(String raw, String signature, ChangeResultMessage message) {
        UUID providerId = providers.current().id();
        Optional<UUID> inboxId = recordInbox(providerId, "CHANGE_RESULT", message.messageId(), raw, signature);
        if (inboxId == null) {
            return InboundResult.REJECTED_SIGNATURE;
        }
        if (inboxId.isEmpty()) {
            return InboundResult.DUPLICATE;
        }
        Optional<UUID> billId = jdbc.sql("SELECT bill_id FROM external_bill WHERE provider_id = :p AND gepg_bill_id = :g")
                .param("p", providerId)
                .param("g", message.gepgBillId())
                .query(UUID.class)
                .optional();
        if (billId.isEmpty()) {
            audit.record("gepg", "CHANGE_RESULT_UNKNOWN_BILL", "gepg-bill:" + message.gepgBillId(), null, null);
            return InboundResult.IGNORED;
        }
        EventSubject subject = bills.lockSubject(billId.get()).orElseThrow();
        record Open(UUID id, java.time.OffsetDateTime newValidUntil) {}
        Optional<Open> request = jdbc.sql("""
                SELECT id, new_valid_until FROM bill_change_request
                 WHERE bill_id = :bill AND kind = :kind AND status IN ('SentToGepg','OutcomeUnknown')
                 FOR UPDATE
                """)
            .param("bill", billId.get())
            .param("kind", message.kind())
            .query((rs, n) -> new Open(rs.getObject(1, UUID.class), rs.getObject(2, java.time.OffsetDateTime.class)))
            .optional();
        if (request.isEmpty()) {
            audit.record("gepg", "CHANGE_RESULT_IGNORED", "bill:" + billId.get(), "no open " + message.kind() + " request", null);
            return InboundResult.IGNORED;
        }
        String cn = jdbc.sql("SELECT control_number FROM external_bill WHERE bill_id = :id").param("id", billId.get()).query(String.class).single();
        Map<String, Object> data = new LinkedHashMap<>();
        if (!message.accepted()) {
            jdbc.sql("UPDATE bill SET registration_state = 'Accepted', row_version = row_version + 1 WHERE id = :id").param("id", billId.get()).update();
            jdbc.sql("UPDATE bill_change_request SET status = 'RefusedByGepg', gepg_code = :code, completed_at = now() WHERE id = :id")
                .param("code", message.responseCode()).param("id", request.get().id()).update();
            audit.record("gepg", message.kind() + "_REFUSED", "bill:" + billId.get(), message.responseCode(), null);
            return InboundResult.ACCEPTED;
        }
        if ("CANCELLATION".equals(message.kind())) {
            jdbc.sql("UPDATE bill SET registration_state = 'Cancelled', row_version = row_version + 1 WHERE id = :id").param("id", billId.get()).update();
            data.put("registrationState", "Cancelled");
            outbox.append(subject, "bill.cancelled", data);
            notices.enqueueForBill(billId.get(), "BILL_CANCELLED", Map.of("cn", NotificationService.controlNumber(cn)));
        } else {
            java.time.OffsetDateTime until = request.get().newValidUntil();
            java.time.LocalDate due = until.atZoneSameInstant(java.time.ZoneId.of("Africa/Dar_es_Salaam")).toLocalDate();
            jdbc.sql("UPDATE bill SET registration_state = 'Accepted', valid_until = :until, due_date = :due, row_version = row_version + 1 WHERE id = :id")
                .param("until", until).param("due", due).param("id", billId.get()).update();
            jdbc.sql("UPDATE external_bill SET expires_at = :until WHERE bill_id = :id").param("until", until).param("id", billId.get()).update();
            data.put("registrationState", "Accepted");
            data.put("dueDate", due);
            data.put("validUntil", until);
            outbox.append(subject, "bill.expiry.changed", data);
            notices.enqueueForBill(billId.get(), "EXPIRY_CHANGED", Map.of("cn", NotificationService.controlNumber(cn), "due", NotificationService.date(due)));
        }
        jdbc.sql("UPDATE bill_change_request SET status = 'Confirmed', gepg_code = :code, completed_at = now() WHERE id = :id")
            .param("code", message.responseCode()).param("id", request.get().id()).update();
        audit.record("gepg", message.kind() + "_CONFIRMED", "bill:" + billId.get(), null, null);
        return InboundResult.ACCEPTED;
    }

    /**
     * Returns null when the signature fails (message quarantined, security case opened),
     * empty when the message was already received, or the new inbox ID.
     */
    private Optional<UUID> recordInbox(UUID providerId, String type, String messageId, String raw, String signature) {
        boolean verified = verifier.verify(raw, signature);
        Optional<UUID> id = jdbc.sql("""
                INSERT INTO integration_inbox (provider_id, message_type, external_message_id, payload_digest, payload,
                                               verification_state, correlation_id)
                VALUES (:p, :type, :mid, :digest, :payload, :state, :cid)
                ON CONFLICT DO NOTHING
                RETURNING id
                """)
            .param("p", providerId)
            .param("type", verified ? type : type + ":REJECTED")
            .param("mid", messageId)
            .param("digest", Digests.sha256(raw))
            .param("payload", raw)
            .param("state", verified ? "VERIFIED" : "REJECTED")
            .param("cid", Correlation.current())
            .query(UUID.class)
            .optional();
        if (!verified) {
            log.warn("GePG {} message {} failed verification", type, messageId);
            if (id.isPresent()) {
                cases.open("SECURITY", null, messageId, null, null, "Inbound " + type + " message failed signature verification", 0);
            }
            return null;
        }
        return id;
    }
}
