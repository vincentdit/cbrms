package tz.go.tirdo.cbrms.reconciliation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tz.go.tirdo.cbrms.audit.AuditService;
import tz.go.tirdo.cbrms.billing.BillService;
import tz.go.tirdo.cbrms.cases.CaseService;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.common.Digests;
import tz.go.tirdo.cbrms.common.Json;
import tz.go.tirdo.cbrms.gepg.GepgGateway;
import tz.go.tirdo.cbrms.gepg.GepgProviders;
import tz.go.tirdo.cbrms.gepg.ReconciliationLine;

/**
 * Daily GePG reconciliation (FR19 to FR22, FR34, SOP-08).
 * Matching uses the GePG payment reference, then amount and currency; amount alone never matches.
 * Re-running a day with identical GePG data returns the existing run; changed data creates a new run.
 */
@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    public record RunResult(UUID batchId, boolean reusedExisting, int matched, int exceptions, int missingInGepg) {}

    private record Local(UUID paymentId, String transactionId, BigDecimal amount, String currency) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final GepgGateway gateway;
    private final GepgProviders providers;
    private final CaseService cases;
    private final AuditService audit;

    public ReconciliationService(JdbcClient jdbc, TransactionTemplate tx, GepgGateway gateway, GepgProviders providers,
                                 CaseService cases, AuditService audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.gateway = gateway;
        this.providers = providers;
        this.cases = cases;
        this.audit = audit;
    }

    /** Runs at 06:30 Dar es Salaam time for the previous business day. */
    @Scheduled(cron = "${cbrms.reconciliation.cron:0 30 6 * * *}", zone = "Africa/Dar_es_Salaam")
    public void scheduled() {
        LocalDate yesterday = LocalDate.now(BillService.BUSINESS_ZONE).minusDays(1);
        try {
            run("cbrms-system", yesterday);
        } catch (RuntimeException e) {
            log.warn("Scheduled reconciliation for {} did not complete: {}", yesterday, e.getMessage());
        }
    }

    public RunResult run(String actor, LocalDate day) {
        if (day.isAfter(LocalDate.now(BillService.BUSINESS_ZONE))) {
            throw ApiException.validation("businessDate", "cannot reconcile a future date");
        }
        List<ReconciliationLine> lines;
        try {
            lines = gateway.reconciliation(day);
        } catch (Exception e) {
            // SOP-08: a missing feed is recorded as missing evidence, never as a zero-collection day
            tx.executeWithoutResult(s -> cases.open("RECONCILIATION_DIFFERENCE", null, "GEPG-" + day, null, null,
                    "GePG reconciliation data for " + day + " was not available: " + e.getMessage(), 1));
            throw new ApiException(503, "CBR-REC-001", "GePG reconciliation data for " + day + " is not available. Recorded as missing evidence.");
        }
        List<ReconciliationLine> sorted = lines.stream().sorted(Comparator.comparing(ReconciliationLine::transactionId)).toList();
        String digest = Digests.sha256(Json.write(sorted.stream()
                .map(l -> List.of(l.transactionId(), String.valueOf(l.controlNumber()), l.amount(), l.currency())).toList()) + "|" + day);
        RunResult result = tx.execute(status -> reconcile(actor, day, sorted, digest));
        audit.record(actor, "RECONCILIATION_RUN", "reconciliation:" + result.batchId(), null,
                Map.of("businessDate", day.toString(), "matched", result.matched(), "exceptions", result.exceptions(),
                        "missingInGepg", result.missingInGepg(), "reused", result.reusedExisting()));
        return result;
    }

    private RunResult reconcile(String actor, LocalDate day, List<ReconciliationLine> lines, String digest) {
        Optional<UUID> existing = jdbc.sql("SELECT id FROM reconciliation_batch WHERE source = 'GEPG' AND digest = :d")
                .param("d", digest).query(UUID.class).optional();
        if (existing.isPresent()) {
            Map<String, Object> b = jdbc.sql("SELECT matched_count, exception_count, missing_count FROM reconciliation_batch WHERE id = :id")
                    .param("id", existing.get()).query().singleRow();
            return new RunResult(existing.get(), true, ((Number) b.get("matched_count")).intValue(),
                    ((Number) b.get("exception_count")).intValue(), ((Number) b.get("missing_count")).intValue());
        }
        UUID providerId = providers.current().id();
        BigDecimal externalTotal = lines.stream().map(ReconciliationLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        UUID batchId = jdbc.sql("""
                INSERT INTO reconciliation_batch (source, batch_reference, business_date, digest)
                VALUES ('GEPG', :ref, :day, :digest) RETURNING id
                """)
            .param("ref", "GEPG-" + day + "-" + digest.substring(0, 10))
            .param("day", day)
            .param("digest", digest)
            .query(UUID.class)
            .single();

        Map<String, Local> local = jdbc.sql("""
                SELECT id, external_transaction_id, amount, currency FROM payment
                 WHERE provider_id = :p AND (received_at AT TIME ZONE 'Africa/Dar_es_Salaam')::date = :day
                """)
            .param("p", providerId).param("day", day)
            .query((rs, n) -> new Local(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getString(4)))
            .list().stream().collect(Collectors.toMap(Local::transactionId, l -> l));

        int matched = 0;
        int exceptions = 0;
        Set<String> seen = new HashSet<>();
        for (ReconciliationLine line : lines) {
            seen.add(line.transactionId());
            Optional<Local> pay = Optional.ofNullable(local.get(line.transactionId()));
            if (pay.isEmpty()) {
                // A payment from an earlier day may still be in CBRMS; look it up by reference before calling it missing
                pay = jdbc.sql("SELECT id, external_transaction_id, amount, currency FROM payment WHERE provider_id = :p AND external_transaction_id = :tx")
                        .param("p", providerId).param("tx", line.transactionId())
                        .query((rs, n) -> new Local(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getString(4)))
                        .optional();
            }
            String status;
            UUID caseId = null;
            if (pay.isPresent() && pay.get().amount().compareTo(line.amount()) == 0 && pay.get().currency().equals(line.currency())) {
                status = "MATCHED";
                matched++;
            } else {
                status = "EXCEPTION";
                exceptions++;
                String detail = pay.isEmpty()
                        ? "In GePG but not in CBRMS: payment " + line.transactionId() + " of " + line.amount().toPlainString() + " " + line.currency()
                        : "Amount or currency differs for " + line.transactionId() + ": GePG " + line.amount().toPlainString() + " " + line.currency()
                                + ", CBRMS " + pay.get().amount().toPlainString() + " " + pay.get().currency();
                caseId = cases.open("RECONCILIATION_DIFFERENCE", billOf(pay.map(Local::paymentId).orElse(null)), line.transactionId(),
                        line.amount(), line.currency(), detail, 1);
            }
            jdbc.sql("""
                    INSERT INTO reconciliation_item (batch_id, external_reference, control_number, currency, amount, occurred_at, match_status, payment_id, case_id)
                    VALUES (:batch, :ref, :cn, :currency, :amount, :at, :status, :payment, :case)
                    """)
                .param("batch", batchId).param("ref", line.transactionId()).param("cn", line.controlNumber())
                .param("currency", line.currency()).param("amount", line.amount()).param("at", line.paidAt())
                .param("status", status).param("payment", pay.map(Local::paymentId).orElse(null)).param("case", caseId)
                .update();
            if (pay.isPresent()) {
                markBill(pay.get().paymentId(), "MATCHED".equals(status) ? "Matched" : "Exception");
            }
        }

        int missing = 0;
        for (Local l : local.values()) {
            if (!seen.contains(l.transactionId())) {
                missing++;
                cases.open("RECONCILIATION_DIFFERENCE", billOf(l.paymentId()), l.transactionId(), l.amount(), l.currency(),
                        "In CBRMS but not in GePG data for " + day + ": payment " + l.transactionId(), 1);
                markBill(l.paymentId(), "Exception");
            }
        }
        BigDecimal localTotal = local.values().stream().map(Local::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        jdbc.sql("""
                UPDATE reconciliation_batch
                   SET status = 'MATCHED', matched_count = :m, exception_count = :e, missing_count = :x,
                       external_total = :ext, local_total = :loc
                 WHERE id = :id
                """)
            .param("m", matched).param("e", exceptions).param("x", missing).param("ext", externalTotal).param("loc", localTotal).param("id", batchId)
            .update();
        return new RunResult(batchId, false, matched, exceptions, missing);
    }

    private UUID billOf(UUID paymentId) {
        if (paymentId == null) {
            return null;
        }
        return jdbc.sql("SELECT bill_id FROM payment_allocation WHERE payment_id = :p UNION SELECT bill_id FROM credit_balance WHERE payment_id = :p LIMIT 1")
                .param("p", paymentId).query(UUID.class).optional().orElse(null);
    }

    /** Matched only moves a bill forward from Pending; an exception always shows. */
    private void markBill(UUID paymentId, String state) {
        UUID billId = billOf(paymentId);
        if (billId == null) {
            return;
        }
        jdbc.sql("""
                UPDATE bill SET reconciliation_state = :state
                 WHERE id = :id AND (reconciliation_state IN ('NotDue','Pending') OR :state = 'Exception')
                """)
            .param("state", state).param("id", billId).update();
    }

    public void review(UUID reviewerId, String reviewer, UUID batchId, String note) {
        int rows = jdbc.sql("""
                UPDATE reconciliation_batch SET status = 'REVIEWED', reviewer_id = :r, reviewed_at = now(), review_note = :note
                 WHERE id = :id AND status = 'MATCHED'
                """)
            .param("r", reviewerId).param("note", note).param("id", batchId).update();
        if (rows == 0) {
            throw ApiException.conflict("CBR-STA-001", "This run is not waiting for review");
        }
        audit.record(reviewer, "RECONCILIATION_REVIEWED", "reconciliation:" + batchId, note, null);
    }
}
