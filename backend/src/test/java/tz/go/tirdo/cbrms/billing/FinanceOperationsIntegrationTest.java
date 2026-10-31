package tz.go.tirdo.cbrms.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.gepg.GepgSubmissionWorker;
import tz.go.tirdo.cbrms.gepg.sim.SimulatorController;
import tz.go.tirdo.cbrms.gepg.sim.SimulatorController.SimulatedPayment;
import tz.go.tirdo.cbrms.gepg.sim.SimulatorGepgGateway;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;
import tz.go.tirdo.cbrms.lookup.PublicLookupController;
import tz.go.tirdo.cbrms.notifications.NotificationDispatcher;
import tz.go.tirdo.cbrms.reconciliation.ReconciliationService;
import tz.go.tirdo.cbrms.refunds.RefundService;

/**
 * Cancellation and replacement (AT16), expiry change (AT20), reconciliation (AT05), refunds (AT10),
 * client notices and OTP lookup (AT19). Skipped unless CBRMS_TEST_DB_URL is set.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CBRMS_TEST_DB_URL", matches = ".+")
class FinanceOperationsIntegrationTest {

    @Autowired BillService billing;
    @Autowired BillChangeService changes;
    @Autowired BillQueries queries;
    @Autowired GepgSubmissionWorker submissions;
    @Autowired SimulatorGepgGateway simulator;
    @Autowired SimulatorController gepg;
    @Autowired ReconciliationService reconciliation;
    @Autowired RefundService refunds;
    @Autowired NotificationDispatcher notifier;
    @Autowired PublicLookupController lookup;
    @Autowired Actors actors;
    @Autowired ApprovalService approvalService;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void cancelThenReplaceKeepsOneActiveBill() {
        Actor lims = as("lims", "PLATFORM");
        String ref = ref();
        UUID original = issue(billing.create(lims, key(), request(ref, null)).billId());

        UUID requestId = changes.requestCancellation(lims, original, "Client changed the sample set").requestId();
        ApiException self = assertThrows(ApiException.class, () -> changes.decide(lims, requestId, "APPROVED", null));
        assertEquals(403, self.status());

        changes.decide(as("finance.approver", "BILL_APPROVER"), requestId, "APPROVED", "Checked");
        assertEquals("CancellationPending", queries.view(original).orElseThrow().externalRegistrationStatus());
        simulator.answerPendingChanges();
        assertEquals("Cancelled", queries.view(original).orElseThrow().externalRegistrationStatus());

        Actor lims2 = as("lims", "PLATFORM");
        BillService.CreateOutcome replacement = billing.create(lims2, key(), request(ref, original));
        assertTrue(replacement.created());
        BillView r = queries.view(replacement.billId()).orElseThrow();
        assertEquals(2, r.liabilityVersion());
        assertEquals("Superseded", queries.view(original).orElseThrow().preparationState());
        assertEquals(List.of(), jdbc.sql("SELECT rule FROM v_invariant_violations WHERE bill_id IN (:a, :b)")
                .param("a", original).param("b", replacement.billId()).query(String.class).list());
        assertTrue(templates(original).contains("BILL_CANCELLED"));
    }

    @Test
    void cancellationRefusedOnceMoneyArrived() {
        Actor lims = as("lims", "PLATFORM");
        UUID id = issue(billing.create(lims, key(), request(ref(), null)).billId());
        pay(queries.view(id).orElseThrow().controlNumber(), "45000.00", null);
        ApiException e = assertThrows(ApiException.class, () -> changes.requestCancellation(as("lims", "PLATFORM"), id, "Too late"));
        assertEquals(409, e.status());
    }

    @Test
    void expiryChangeMovesDueDate() {
        Actor lims = as("lims", "PLATFORM");
        UUID id = issue(billing.create(lims, key(), request(ref(), null)).billId());
        LocalDate newDue = LocalDate.parse(queries.view(id).orElseThrow().dueDate()).plusDays(14);
        UUID requestId = changes.requestExpiryChange(as("finance.maker", "BILLING_OFFICER"), id, newDue, "Client asked for more time").requestId();
        changes.decide(as("finance.approver", "BILL_APPROVER"), requestId, "APPROVED", null);
        simulator.answerPendingChanges();
        BillView v = queries.view(id).orElseThrow();
        assertEquals("Accepted", v.externalRegistrationStatus());
        assertEquals(newDue.toString(), v.dueDate());
        assertTrue(templates(id).contains("EXPIRY_CHANGED"));
    }

    @Test
    void reconciliationMatchesAndFlagsDifferences() {
        Actor lims = as("lims", "PLATFORM");
        UUID id = issue(billing.create(lims, key(), request(ref(), null)).billId());
        String tx = "TX-" + UUID.randomUUID();
        pay(queries.view(id).orElseThrow().controlNumber(), "45000.00", tx);
        LocalDate today = LocalDate.now(BillService.BUSINESS_ZONE);
        String ghost = "GHOST-" + UUID.randomUUID();
        simulator.addExtraReconciliationLine(today, ghost, "990000000001", new BigDecimal("1000.00"));

        ReconciliationService.RunResult run = reconciliation.run("test", today);
        assertFalse(run.reusedExisting());
        assertEquals("MATCHED", jdbc.sql("SELECT match_status FROM reconciliation_item WHERE batch_id = :b AND external_reference = :tx")
                .param("b", run.batchId()).param("tx", tx).query(String.class).single());
        assertEquals("EXCEPTION", jdbc.sql("SELECT match_status FROM reconciliation_item WHERE batch_id = :b AND external_reference = :tx")
                .param("b", run.batchId()).param("tx", ghost).query(String.class).single());
        assertEquals("Matched", jdbc.sql("SELECT reconciliation_state FROM bill WHERE id = :id").param("id", id).query(String.class).single());

        ReconciliationService.RunResult again = reconciliation.run("test", today);
        assertTrue(again.reusedExisting(), "identical GePG data must not create a second run");
        assertEquals(run.batchId(), again.batchId());
    }

    @Test
    void refundNeedsSecondOfficerAndBankReference() {
        Actor lims = as("lims", "PLATFORM");
        UUID id = billing.create(lims, key(), new CreateBillRequest(ref(), payer(), "TZS", "Microbiology", 30,
                List.of(new CreateBillRequest.Line("LIMS-MICRO-002", 1, BigDecimal.ONE)), null, "en")).billId();
        Actor approver = as("finance.approver", "BILL_APPROVER");
        approvalService.decide(approver, id, new ApprovalRequest("APPROVED", null));
        issue(id);
        pay(queries.view(id).orElseThrow().controlNumber(), "70000.00", null);
        UUID credit = jdbc.sql("SELECT id FROM credit_balance WHERE bill_id = :id").param("id", id).query(UUID.class).single();

        Actor maker = as("finance.maker", "BILLING_OFFICER");
        UUID refund = refunds.request(maker, credit, new BigDecimal("10000.00"), "Overpaid by client");
        assertEquals(403, assertThrows(ApiException.class, () -> refunds.decide(maker, refund, "APPROVED", null)).status());
        refunds.decide(as("finance.approver", "BILL_APPROVER"), refund, "APPROVED", null);
        assertThrows(ApiException.class, () -> refunds.recordExecution(maker, refund, " ", null));
        refunds.recordExecution(maker, refund, "CRDB-TRF-0001", null);
        assertEquals("REFUNDED", jdbc.sql("SELECT status FROM credit_balance WHERE id = :id").param("id", credit).query(String.class).single());
    }

    @Test
    void clientGetsNoticesAndCanLookUpWithCode() {
        Actor lims = as("lims", "PLATFORM");
        UUID id = issue(billing.create(lims, key(), request(ref(), null)).billId());
        String cn = queries.view(id).orElseThrow().controlNumber();
        assertTrue(templates(id).contains("CONTROL_NUMBER_ISSUED"));

        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setRemoteAddr("10.9.9." + (int) (Math.random() * 200));
        lookup.sendCode(new PublicLookupController.CodeRequest(cn), http);
        String body = jdbc.sql("SELECT body FROM client_notification WHERE bill_id = :id AND template = 'LOOKUP_CODE' ORDER BY created_at DESC LIMIT 1")
                .param("id", id).query(String.class).single();
        String code = body.replaceAll("(?s).*?(\\d{6}).*", "$1");

        assertEquals(400, assertThrows(ApiException.class,
                () -> lookup.verify(new PublicLookupController.VerifyRequest(cn, code.equals("000000") ? "111111" : "000000"), http)).status());
        PublicLookupController.PublicBill bill = lookup.verify(new PublicLookupController.VerifyRequest(cn, code), http);
        assertEquals("45000.00", bill.amount());
        assertFalse(bill.payer().contains("Client Ltd"), "payer name is masked");
        assertEquals(400, assertThrows(ApiException.class, () -> lookup.verify(new PublicLookupController.VerifyRequest(cn, code), http)).status(),
                "a code works once");

        notifier.runOnce();
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM client_notification WHERE bill_id = :id AND status = 'PENDING'")
                .param("id", id).query(Long.class).single().longValue());
    }

    // ---- helpers ----

    private UUID issue(UUID id) {
        submissions.runOnce();
        simulator.issuePendingControlNumbers();
        assertNotNull(queries.view(id).orElseThrow().controlNumber());
        return id;
    }

    private Map<String, Object> pay(String controlNumber, String amount, String tx) {
        return gepg.pay(new SimulatedPayment(controlNumber, new BigDecimal(amount), null, null, tx, null, null, false));
    }

    private List<String> templates(UUID billId) {
        return jdbc.sql("SELECT template FROM client_notification WHERE bill_id = :id").param("id", billId).query(String.class).list();
    }

    private Actor as(String user, String... roles) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, "n/a",
                Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return actors.current();
    }

    private static CreateBillRequest.Payer payer() {
        return new CreateBillRequest.Payer("CUST-" + UUID.randomUUID().toString().substring(0, 6), "Test Client Ltd", "255700000000", "client@example.com");
    }

    private static CreateBillRequest request(String ref, UUID replaces) {
        return new CreateBillRequest(ref, payer(), "TZS", "Laboratory request " + ref, 30,
                List.of(new CreateBillRequest.Line("LIMS-CHEM-001", 1, BigDecimal.ONE)), replaces, null);
    }

    private static String ref() {
        return "LAB-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
