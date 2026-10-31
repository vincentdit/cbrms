package tz.go.tirdo.cbrms.gepg;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.go.tirdo.cbrms.audit.AuditService;
import tz.go.tirdo.cbrms.billing.BillQueries;
import tz.go.tirdo.cbrms.common.Digests;
import tz.go.tirdo.cbrms.common.Json;
import tz.go.tirdo.cbrms.events.EventSubject;
import tz.go.tirdo.cbrms.events.OutboxService;

/**
 * Registers approved bills with GePG (Blueprint section 4.2, FR08).
 * The stable GePG bill ID and request digest are committed before the call, so a lost response
 * leaves the bill in AckUnknown instead of creating a second bill.
 */
@Component
public class GepgSubmissionWorker {
    private static final Logger log = LoggerFactory.getLogger(GepgSubmissionWorker.class);

    record Prepared(UUID attemptId, BillSubmission submission) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final GepgGateway gateway;
    private final GepgProviders providers;
    private final BillQueries bills;
    private final OutboxService outbox;
    private final AuditService audit;

    public GepgSubmissionWorker(JdbcClient jdbc, TransactionTemplate tx, GepgGateway gateway, GepgProviders providers,
                                BillQueries bills, OutboxService outbox, AuditService audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.gateway = gateway;
        this.providers = providers;
        this.bills = bills;
        this.outbox = outbox;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${cbrms.gepg.submission-interval:PT5S}")
    public void scheduled() {
        runOnce();
    }

    public int runOnce() {
        List<Prepared> batch = tx.execute(status -> prepare());
        if (batch == null) {
            return 0;
        }
        for (Prepared p : batch) {
            send(p);
        }
        return batch.size();
    }

    private List<Prepared> prepare() {
        GepgProviders.Provider provider = providers.current();
        List<UUID> ids = jdbc.sql("""
                SELECT id FROM bill
                 WHERE preparation_state = 'Approved' AND registration_state = 'NotSubmitted'
                 ORDER BY created_at
                 LIMIT 20
                   FOR UPDATE SKIP LOCKED
                """)
            .query(UUID.class)
            .list();
        List<Prepared> out = new ArrayList<>();
        for (UUID billId : ids) {
            BillSubmission s = build(billId, provider);
            UUID externalId = jdbc.sql("""
                    INSERT INTO external_bill (bill_id, provider_id, gepg_bill_id, expires_at)
                    VALUES (:bill, :provider, :gepgBillId, :expires)
                    ON CONFLICT (bill_id) DO UPDATE SET expires_at = EXCLUDED.expires_at
                    RETURNING id
                    """)
                .param("bill", billId)
                .param("provider", provider.id())
                .param("gepgBillId", s.gepgBillId())
                .param("expires", s.expiresAt())
                .query(UUID.class)
                .single();
            UUID attemptId = jdbc.sql("""
                    INSERT INTO submission_attempt (external_bill_id, kind, request_digest, state)
                    VALUES (:ext, 'SUBMIT', :digest, 'SENT')
                    RETURNING id
                    """)
                .param("ext", externalId)
                .param("digest", Digests.sha256(Json.write(asMap(s))))
                .query(UUID.class)
                .single();
            jdbc.sql("UPDATE bill SET registration_state = 'Submitted', row_version = row_version + 1 WHERE id = :id")
                .param("id", billId)
                .update();
            out.add(new Prepared(attemptId, s));
        }
        return out;
    }

    private void send(Prepared p) {
        UUID billId = p.submission().billId();
        try {
            SubmitResult result = gateway.submit(p.submission());
            tx.executeWithoutResult(status -> {
                jdbc.sql("UPDATE submission_attempt SET state = :state, response_code = :code, response_digest = :digest WHERE id = :id")
                    .param("state", result.acknowledged() ? "ACKNOWLEDGED" : "REJECTED")
                    .param("code", result.responseCode())
                    .param("digest", result.responseDigest())
                    .param("id", p.attemptId())
                    .update();
                if (result.acknowledged()) {
                    jdbc.sql("UPDATE external_bill SET acknowledged_at = coalesce(acknowledged_at, now()) WHERE bill_id = :id")
                        .param("id", billId)
                        .update();
                    // The control number may already have arrived; only move forward from Submitted.
                    jdbc.sql("UPDATE bill SET registration_state = 'AwaitingControlNumber' WHERE id = :id AND registration_state = 'Submitted'")
                        .param("id", billId)
                        .update();
                } else {
                    EventSubject subject = bills.lockSubject(billId).orElseThrow();
                    int rows = jdbc.sql("UPDATE bill SET registration_state = 'Rejected', row_version = row_version + 1 WHERE id = :id AND registration_state = 'Submitted'")
                        .param("id", billId)
                        .update();
                    if (rows == 1) {
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("registrationState", "Rejected");
                        data.put("responseCode", result.responseCode());
                        outbox.append(subject, "bill.registration.rejected", data);
                    }
                    audit.record("cbrms-system", "BILL_SUBMISSION_REJECTED", "bill:" + billId, result.responseCode(), null);
                }
            });
        } catch (GepgUnavailableException | RuntimeException e) {
            log.warn("GePG submission outcome unknown for bill {}: {}", billId, e.getMessage());
            tx.executeWithoutResult(status -> {
                jdbc.sql("UPDATE submission_attempt SET state = 'UNKNOWN' WHERE id = :id").param("id", p.attemptId()).update();
                jdbc.sql("UPDATE bill SET registration_state = 'AckUnknown' WHERE id = :id AND registration_state = 'Submitted'")
                    .param("id", billId)
                    .update();
                audit.record("cbrms-system", "BILL_SUBMISSION_UNKNOWN", "bill:" + billId, e.getClass().getSimpleName(), null);
            });
        }
    }

    private BillSubmission build(UUID billId, GepgProviders.Provider provider) {
        record Head(BigDecimal total, String currency, String mode, OffsetDateTime validUntil, String description,
                    String payerRef, String payerName, String phone, String email) {}
        Head h = jdbc.sql("""
                SELECT b.total, b.currency, b.payment_mode, b.valid_until, b.description, p.payer_reference, p.display_name,
                       (SELECT value FROM payer_contact c WHERE c.payer_id = p.id AND c.channel = 'PHONE' ORDER BY created_at DESC LIMIT 1),
                       (SELECT value FROM payer_contact c WHERE c.payer_id = p.id AND c.channel = 'EMAIL' ORDER BY created_at DESC LIMIT 1)
                  FROM bill b JOIN payer p ON p.id = b.payer_id
                 WHERE b.id = :id
                """)
            .param("id", billId)
            .query((rs, n) -> new Head(rs.getBigDecimal(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)))
            .single();
        List<BillSubmission.Item> items = jdbc.sql("""
                SELECT gfs_code, sum(line_total) FROM bill_line WHERE bill_id = :id GROUP BY gfs_code ORDER BY gfs_code
                """)
            .param("id", billId)
            .query((rs, n) -> new BillSubmission.Item(rs.getString(1), rs.getBigDecimal(2)))
            .list();
        return new BillSubmission(billId, billId.toString(), provider.spCode(), provider.subSpCode(), provider.systemId(),
                h.total(), h.currency(), h.mode(), h.validUntil(), h.payerRef(), h.payerName(), h.phone(), h.email(), h.description(), items);
    }

    private static Map<String, Object> asMap(BillSubmission s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gepgBillId", s.gepgBillId());
        m.put("spCode", s.spCode());
        m.put("amount", s.amount());
        m.put("currency", s.currency());
        m.put("paymentMode", s.paymentMode());
        m.put("expiresAt", s.expiresAt());
        m.put("payerReference", s.payerReference());
        m.put("items", s.items().stream().map(i -> List.of(i.gfsCode(), i.amount())).toList());
        return m;
    }
}
