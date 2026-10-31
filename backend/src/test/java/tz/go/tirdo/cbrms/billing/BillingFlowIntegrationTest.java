package tz.go.tirdo.cbrms.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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

/**
 * End-to-end tests against a real PostgreSQL database (AT02, AT03, AT06, AT08, AT10, AT13).
 * Skipped unless CBRMS_TEST_DB_URL is set; see README for the PowerShell commands.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CBRMS_TEST_DB_URL", matches = ".+")
class BillingFlowIntegrationTest {

    @Autowired BillService billing;
    @Autowired ApprovalService approvals;
    @Autowired BillQueries queries;
    @Autowired GepgSubmissionWorker submissions;
    @Autowired SimulatorGepgGateway simulator;
    @Autowired SimulatorController gepg;
    @Autowired Actors actors;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void billToPaymentEndToEnd() {
        Actor lims = as("lims", "PLATFORM");
        UUID id = billing.create(lims, key(), request(ref(), "LIMS-CHEM-001", "2")).billId();
        BillView v = issue(id);
        assertEquals("90000.00", v.total());
        assertEquals("Accepted", v.externalRegistrationStatus());
        assertTrue(v.controlNumber().startsWith("99"));

        assertEquals("ACCEPTED", pay(v.controlNumber(), "90000.00", null, null).get("result"));
        v = queries.view(id).orElseThrow();
        assertEquals("Paid", v.collectionState());
        assertEquals("Eligible", v.releaseState());
        assertEquals(List.of("bill.created", "bill.control-number.issued", "payment.received"), eventTypes(id));
        assertNoInvariantViolations(id);
    }

    @Test
    void duplicatePaymentMessagesPostOnce() {
        Actor lims = as("lims", "PLATFORM");
        UUID id = billing.create(lims, key(), request(ref(), "LIMS-CHEM-001", "1")).billId();
        BillView v = issue(id);
        String tx = "TX-" + UUID.randomUUID();
        String messageId = UUID.randomUUID().toString();

        assertEquals("ACCEPTED", pay(v.controlNumber(), "45000.00", tx, messageId).get("result"));
        assertEquals("DUPLICATE", pay(v.controlNumber(), "45000.00", tx, messageId).get("result"));                       // same message
        assertEquals("DUPLICATE", pay(v.controlNumber(), "45000.00", tx, UUID.randomUUID().toString()).get("result"));    // same payment, other message

        assertEquals(1L, jdbc.sql("SELECT count(*) FROM payment WHERE external_transaction_id = :tx").param("tx", tx).query(Long.class).single().longValue());
        assertEquals(0, new BigDecimal("45000.00").compareTo(allocated(id)));
        assertNoInvariantViolations(id);
    }

    @Test
    void tamperedSignatureHasNoFinancialEffect() {
        Actor lims = as("lims", "PLATFORM");
        UUID id = billing.create(lims, key(), request(ref(), "LIMS-CHEM-001", "1")).billId();
        BillView v = issue(id);
        String tx = "TX-" + UUID.randomUUID();
        Map<String, Object> r = gepg.pay(new SimulatedPayment(v.controlNumber(), new BigDecimal("45000.00"), null, null, tx, null, null, true));
        assertEquals("REJECTED_SIGNATURE", r.get("result"));
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM payment WHERE external_transaction_id = :tx").param("tx", tx).query(Long.class).single().longValue());
        assertEquals("Unpaid", queries.view(id).orElseThrow().collectionState());
    }

    @Test
    void unknownControlNumberGoesToSuspense() {
        String tx = "TX-" + UUID.randomUUID();
        assertEquals("SUSPENSE", pay("990000000000", "1000.00", tx, null).get("result"));
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM exception_case WHERE type = 'UNMATCHED_PAYMENT' AND reference = :tx")
                .param("tx", tx).query(Long.class).single().longValue());
    }

    @Test
    void createIsIdempotent() {
        Actor lims = as("lims", "PLATFORM");
        String ref = ref();
        String key = key();
        BillService.CreateOutcome first = billing.create(lims, key, request(ref, "LIMS-CHEM-001", "1"));
        BillService.CreateOutcome retry = billing.create(lims, key, request(ref, "LIMS-CHEM-001", "1.0"));
        assertTrue(first.created());
        assertFalse(retry.created());
        assertEquals(first.billId(), retry.billId());

        ApiException conflict = assertThrows(ApiException.class, () -> billing.create(lims, key, request(ref, "LIMS-CHEM-001", "3")));
        assertEquals(409, conflict.status());

        BillService.CreateOutcome otherKey = billing.create(lims, key(), request(ref, "LIMS-CHEM-001", "1"));
        assertEquals(first.billId(), otherKey.billId());   // one active bill per liability (BR03)
    }

    @Test
    void makerCannotApproveOwnBill() {
        Actor lims = as("lims", "PLATFORM");
        UUID id = billing.create(lims, key(), request(ref(), "LIMS-MICRO-002", "1")).billId();
        assertEquals("AwaitingApproval", queries.view(id).orElseThrow().preparationState());

        ApiException denied = assertThrows(ApiException.class, () -> approvals.decide(lims, id, new ApprovalRequest("APPROVED", null)));
        assertEquals(403, denied.status());

        Actor approver = as("finance.approver", "BILL_APPROVER");
        approvals.decide(approver, id, new ApprovalRequest("APPROVED", "Checked against request"));
        assertEquals("Approved", queries.view(id).orElseThrow().preparationState());
    }

    @Test
    void partialModeInstalmentsThenOverpayment() {
        Actor lims = as("lims", "PLATFORM");
        UUID id = billing.create(lims, key(), request(ref(), "LIMS-MICRO-002", "1")).billId();
        approvals.decide(as("finance.approver", "BILL_APPROVER"), id, new ApprovalRequest("APPROVED", null));
        BillView v = issue(id);

        pay(v.controlNumber(), "20000.00", null, null);
        assertEquals("PartiallyPaid", queries.view(id).orElseThrow().collectionState());

        pay(v.controlNumber(), "50000.00", null, null);
        v = queries.view(id).orElseThrow();
        assertEquals("Overpaid", v.collectionState());
        assertEquals(0, new BigDecimal("60000.00").compareTo(allocated(id)));
        BigDecimal credit = jdbc.sql("SELECT sum(amount) FROM credit_balance WHERE bill_id = :id").param("id", id).query(BigDecimal.class).single();
        assertEquals(0, new BigDecimal("10000.00").compareTo(credit));
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM exception_case WHERE bill_id = :id AND type = 'AMOUNT_MISMATCH'")
                .param("id", id).query(Long.class).single().longValue());
        assertNoInvariantViolations(id);
    }

    // ---- helpers ----

    private BillView issue(UUID id) {
        submissions.runOnce();
        simulator.issuePendingControlNumbers();
        BillView v = queries.view(id).orElseThrow();
        assertNotNull(v.controlNumber(), "control number issued");
        return v;
    }

    private Map<String, Object> pay(String controlNumber, String amount, String tx, String messageId) {
        return gepg.pay(new SimulatedPayment(controlNumber, new BigDecimal(amount), null, null, tx, messageId, null, false));
    }

    private Actor as(String user, String... roles) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, "n/a",
                Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return actors.current();
    }

    private static CreateBillRequest request(String ref, String service, String quantity) {
        return new CreateBillRequest(ref,
                new CreateBillRequest.Payer("CUST-" + ref, "Test Client Ltd", "255700000000", "client@example.com"),
                "TZS", "Laboratory request " + ref, 30,
                List.of(new CreateBillRequest.Line(service, 1, new BigDecimal(quantity))), null, null);
    }

    private BigDecimal allocated(UUID id) {
        return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM payment_allocation WHERE bill_id = :id").param("id", id).query(BigDecimal.class).single();
    }

    private List<String> eventTypes(UUID id) {
        return jdbc.sql("SELECT event_type FROM outbox_event WHERE aggregate_id = :id ORDER BY sequence").param("id", id).query(String.class).list();
    }

    private void assertNoInvariantViolations(UUID id) {
        List<String> rules = jdbc.sql("SELECT rule FROM v_invariant_violations WHERE bill_id = :id").param("id", id).query(String.class).list();
        assertEquals(List.of(), rules);
        assertNotEquals(0L, jdbc.sql("SELECT count(*) FROM audit_event WHERE object_ref = :ref").param("ref", "bill:" + id).query(Long.class).single().longValue());
    }

    private static String ref() {
        return "LAB-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
