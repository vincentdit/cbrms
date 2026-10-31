package tz.go.tirdo.cbrms.payments;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.cbrms.audit.AuditService;
import tz.go.tirdo.cbrms.billing.BillQueries;
import tz.go.tirdo.cbrms.cases.CaseService;
import tz.go.tirdo.cbrms.domain.AllocationRules;
import tz.go.tirdo.cbrms.domain.BillStates;
import tz.go.tirdo.cbrms.domain.PaymentMode;
import tz.go.tirdo.cbrms.events.EventSubject;
import tz.go.tirdo.cbrms.events.OutboxService;
import tz.go.tirdo.cbrms.gepg.InboundResult;
import tz.go.tirdo.cbrms.gepg.PaymentMessage;
import tz.go.tirdo.cbrms.notifications.NotificationService;

/**
 * Posts a verified GePG payment exactly once (Blueprint section 5; FR13 to FR17).
 * Runs inside the inbound transaction: payment, allocation, credit, bill state and outbox event commit together.
 */
@Service
public class PaymentService {

    private record BillRow(BigDecimal total, String currency, PaymentMode mode, UUID payerId, String registrationState,
                           OffsetDateTime validUntil, String releaseState) {}

    private final JdbcClient jdbc;
    private final BillQueries bills;
    private final OutboxService outbox;
    private final CaseService cases;
    private final AuditService audit;
    private final NotificationService notices;

    public PaymentService(JdbcClient jdbc, BillQueries bills, OutboxService outbox, CaseService cases, AuditService audit,
                          NotificationService notices) {
        this.jdbc = jdbc;
        this.bills = bills;
        this.outbox = outbox;
        this.cases = cases;
        this.audit = audit;
        this.notices = notices;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public InboundResult post(UUID providerId, UUID inboxId, PaymentMessage m) {
        // 1. One financial effect per GePG transaction, whichever message type carried it.
        Optional<UUID> paymentId = jdbc.sql("""
                INSERT INTO payment (provider_id, external_transaction_id, inbox_id, control_number, channel, currency, amount, occurred_at)
                VALUES (:p, :tx, :inbox, :cn, :channel, :currency, :amount, :paidAt)
                ON CONFLICT DO NOTHING
                RETURNING id
                """)
            .param("p", providerId)
            .param("tx", m.transactionId())
            .param("inbox", inboxId)
            .param("cn", m.controlNumber())
            .param("channel", m.channel())
            .param("currency", m.currency())
            .param("amount", m.amount())
            .param("paidAt", m.paidAt())
            .query(UUID.class)
            .optional();
        if (paymentId.isEmpty()) {
            audit.record("gepg", "PAYMENT_DUPLICATE", "gepg-tx:" + m.transactionId(), null, null);
            return InboundResult.DUPLICATE;
        }

        // 2. Resolve the bill only from the verified control number. Never by payer name or amount (SOP-06).
        Optional<UUID> billId = jdbc.sql("SELECT bill_id FROM external_bill WHERE provider_id = :p AND control_number = :cn")
                .param("p", providerId)
                .param("cn", m.controlNumber())
                .query(UUID.class)
                .optional();
        if (billId.isEmpty()) {
            cases.open("UNMATCHED_PAYMENT", null, m.transactionId(), m.amount(), m.currency(),
                    "Verified payment for unknown control number " + m.controlNumber(), 1);
            audit.record("gepg", "PAYMENT_SUSPENSE", "payment:" + paymentId.get(), "unknown control number", null);
            return InboundResult.SUSPENSE;
        }

        // 3. Lock the bill, then read its state.
        EventSubject subject = bills.lockSubject(billId.get()).orElseThrow();
        BillRow b = jdbc.sql("""
                SELECT total, currency, payment_mode, payer_id, registration_state, valid_until, release_state
                  FROM bill WHERE id = :id
                """)
            .param("id", billId.get())
            .query((rs, n) -> new BillRow(rs.getBigDecimal(1), rs.getString(2), PaymentMode.valueOf(rs.getString(3)),
                    rs.getObject(4, UUID.class), rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getString(7)))
            .single();

        if (!b.currency().equals(m.currency())) {
            cases.open("UNMATCHED_PAYMENT", billId.get(), m.transactionId(), m.amount(), m.currency(),
                    "Currency " + m.currency() + " does not match bill currency " + b.currency(), 1);
            return InboundResult.SUSPENSE;
        }

        BigDecimal netAllocated = jdbc.sql("SELECT coalesce(sum(amount), 0) FROM payment_allocation WHERE bill_id = :id")
                .param("id", billId.get())
                .query(BigDecimal.class)
                .single();
        AllocationRules.Decision d = AllocationRules.decide(b.mode(), b.total(), netAllocated, m.amount());

        // 4. Append ledger records.
        if (d.allocate().signum() > 0) {
            jdbc.sql("INSERT INTO payment_allocation (payment_id, bill_id, kind, amount) VALUES (:pay, :bill, 'ALLOCATION', :amount)")
                .param("pay", paymentId.get())
                .param("bill", billId.get())
                .param("amount", d.allocate())
                .update();
        }
        if (d.excess().signum() > 0) {
            jdbc.sql("INSERT INTO credit_balance (payer_id, payment_id, bill_id, amount) VALUES (:payer, :pay, :bill, :amount)")
                .param("payer", b.payerId())
                .param("pay", paymentId.get())
                .param("bill", billId.get())
                .param("amount", d.excess())
                .update();
        }

        // 5. Exceptions are recorded, never used to discard money.
        if (d.exceptionRequired()) {
            cases.open("AMOUNT_MISMATCH", billId.get(), m.transactionId(), m.amount(), m.currency(), d.exceptionReason(), 1);
        }
        if (b.registrationState().equals("Cancelled") || b.registrationState().equals("CancellationPending")) {
            cases.open("PAYMENT_ON_CANCELLED_BILL", billId.get(), m.transactionId(), m.amount(), m.currency(),
                    "Payment received while bill is " + b.registrationState(), 1);
        }
        if (m.paidAt().isAfter(b.validUntil())) {
            cases.open("LATE_PAYMENT", billId.get(), m.transactionId(), m.amount(), m.currency(),
                    "Payment time " + m.paidAt() + " is after bill validity " + b.validUntil(), 1);
        }

        // 6. Update cached state in the same transaction (the nightly check compares it with the ledger).
        boolean paid = d.newState() == BillStates.Collection.Paid || d.newState() == BillStates.Collection.Overpaid;
        String release = paid && b.releaseState().equals("Pending") ? "Eligible" : b.releaseState();
        jdbc.sql("""
                UPDATE bill
                   SET collection_state = :collection,
                       reconciliation_state = CASE WHEN reconciliation_state = 'NotDue' THEN 'Pending' ELSE reconciliation_state END,
                       release_state = :release,
                       row_version = row_version + 1
                 WHERE id = :id
                """)
            .param("collection", d.newState().name())
            .param("release", release)
            .param("id", billId.get())
            .update();

        // 7. Event for the source platform, committed with the payment.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentReference", m.transactionId());
        data.put("controlNumber", m.controlNumber());
        data.put("amount", m.amount());
        data.put("currency", m.currency());
        data.put("allocated", d.allocate());
        data.put("excess", d.excess());
        data.put("outstanding", b.total().subtract(netAllocated).subtract(d.allocate()).max(BigDecimal.ZERO));
        data.put("collectionState", d.newState());
        data.put("releaseState", release);
        data.put("paidAt", m.paidAt());
        outbox.append(subject, "payment.received", data);
        notices.enqueueForBill(billId.get(), "PAYMENT_RECEIVED", Map.of(
                "amount", NotificationService.amount(m.amount()),
                "cn", NotificationService.controlNumber(m.controlNumber()),
                "outstanding", NotificationService.amount(b.total().subtract(netAllocated).subtract(d.allocate()).max(BigDecimal.ZERO))));
        audit.record("gepg", "PAYMENT_POSTED", "payment:" + paymentId.get(), null,
                Map.of("bill", billId.get(), "allocated", d.allocate(), "excess", d.excess()));
        return InboundResult.ACCEPTED;
    }
}
