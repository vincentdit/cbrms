package tz.go.tirdo.cbrms.gepg.sim;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.go.tirdo.cbrms.common.Digests;
import tz.go.tirdo.cbrms.common.Json;
import tz.go.tirdo.cbrms.gepg.BillChangeSubmission;
import tz.go.tirdo.cbrms.gepg.BillSubmission;
import tz.go.tirdo.cbrms.gepg.ChangeResultMessage;
import tz.go.tirdo.cbrms.gepg.ControlNumberMessage;
import tz.go.tirdo.cbrms.gepg.GepgGateway;
import tz.go.tirdo.cbrms.gepg.GepgInboundService;
import tz.go.tirdo.cbrms.gepg.InboundResult;
import tz.go.tirdo.cbrms.gepg.ReconciliationLine;
import tz.go.tirdo.cbrms.gepg.SubmitResult;

/**
 * GePG simulator (development and tests only). Acknowledges every request, then answers asynchronously
 * through the same signed inbound path real GePG messages use. Simulator control numbers start with 99.
 * Reconciliation returns the payments recorded for the simulator provider, plus any extra lines a tester adds
 * to exercise "in GePG but not in CBRMS" differences.
 */
@Component
@ConditionalOnProperty(name = "cbrms.gepg.mode", havingValue = "simulator")
public class SimulatorGepgGateway implements GepgGateway {
    private final Queue<String> pendingBills = new ConcurrentLinkedQueue<>();
    private final Queue<BillChangeSubmission> pendingChanges = new ConcurrentLinkedQueue<>();
    private final List<Map.Entry<LocalDate, ReconciliationLine>> extraLines = new CopyOnWriteArrayList<>();
    private final SecureRandom random = new SecureRandom();
    private final GepgInboundService inbound;
    private final SimulatorSigner signer;
    private final JdbcClient jdbc;

    public SimulatorGepgGateway(GepgInboundService inbound, SimulatorSigner signer, JdbcClient jdbc) {
        this.inbound = inbound;
        this.signer = signer;
        this.jdbc = jdbc;
    }

    @Override
    public SubmitResult submit(BillSubmission submission) {
        pendingBills.add(submission.gepgBillId());
        return new SubmitResult(true, "SIM-7101", Digests.sha256("ack:" + submission.gepgBillId()));
    }

    @Override
    public SubmitResult requestChange(BillChangeSubmission change) {
        pendingChanges.add(change);
        return new SubmitResult(true, "SIM-7101", Digests.sha256("change:" + change.changeRequestId()));
    }

    @Override
    public List<ReconciliationLine> reconciliation(LocalDate businessDate) {
        List<ReconciliationLine> lines = new ArrayList<>(jdbc.sql("""
                SELECT p.external_transaction_id, p.control_number, p.amount, p.currency, p.occurred_at
                  FROM payment p JOIN gepg_provider g ON g.id = p.provider_id
                 WHERE g.environment = 'SIMULATOR'
                   AND (p.received_at AT TIME ZONE 'Africa/Dar_es_Salaam')::date = :day
                 ORDER BY p.received_at
                """)
            .param("day", businessDate)
            .query((rs, n) -> new ReconciliationLine(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4),
                    rs.getObject(5, OffsetDateTime.class)))
            .list());
        extraLines.stream().filter(e -> e.getKey().equals(businessDate)).forEach(e -> lines.add(e.getValue()));
        return lines;
    }

    /** Test aid: adds a payment that GePG reports but CBRMS never received. */
    public void addExtraReconciliationLine(LocalDate day, String transactionId, String controlNumber, BigDecimal amount) {
        extraLines.add(Map.entry(day, new ReconciliationLine(transactionId, controlNumber, amount, "TZS", OffsetDateTime.now())));
    }

    @Scheduled(fixedDelayString = "${cbrms.gepg.simulator-interval:PT2S}")
    public void scheduled() {
        issuePendingControlNumbers();
        answerPendingChanges();
    }

    /** Issues a control number for every acknowledged bill. Returns how many were issued. */
    public int issuePendingControlNumbers() {
        int issued = 0;
        String gepgBillId;
        while ((gepgBillId = pendingBills.poll()) != null) {
            String controlNumber = "99" + String.format("%010d", Math.floorMod(random.nextLong(), 10_000_000_000L));
            ControlNumberMessage message = new ControlNumberMessage(UUID.randomUUID().toString(), gepgBillId, true, controlNumber, "SIM-7101");
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("messageId", message.messageId());
            raw.put("gepgBillId", gepgBillId);
            raw.put("status", "ACCEPTED");
            raw.put("controlNumber", controlNumber);
            String body = Json.write(raw);
            if (inbound.receiveControlNumber(body, signer.sign(body), message) == InboundResult.ACCEPTED) {
                issued++;
            }
        }
        return issued;
    }

    /** Confirms every pending cancellation or expiry change. Returns how many were answered. */
    public int answerPendingChanges() {
        int answered = 0;
        BillChangeSubmission change;
        while ((change = pendingChanges.poll()) != null) {
            ChangeResultMessage message = new ChangeResultMessage(UUID.randomUUID().toString(), change.gepgBillId(), change.kind(), true, "SIM-7101");
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("messageId", message.messageId());
            raw.put("gepgBillId", change.gepgBillId());
            raw.put("kind", change.kind());
            raw.put("status", "ACCEPTED");
            String body = Json.write(raw);
            if (inbound.receiveChangeResult(body, signer.sign(body), message) == InboundResult.ACCEPTED) {
                answered++;
            }
        }
        return answered;
    }
}
