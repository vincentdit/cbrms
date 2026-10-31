package tz.go.tirdo.cbrms.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
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
import tz.go.tirdo.cbrms.billing.BillQueries;
import tz.go.tirdo.cbrms.billing.BillService;
import tz.go.tirdo.cbrms.billing.CreateBillRequest;
import tz.go.tirdo.cbrms.cases.CaseWorkService;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.gepg.GepgSubmissionWorker;
import tz.go.tirdo.cbrms.gepg.sim.SimulatorController;
import tz.go.tirdo.cbrms.gepg.sim.SimulatorGepgGateway;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;

/** Suspense resolution, reversal and reallocation under maker-checker (FR18, FR21, AT14). */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CBRMS_TEST_DB_URL", matches = ".+")
class CorrectionIntegrationTest {

    @Autowired CorrectionService corrections;
    @Autowired CaseWorkService caseWork;
    @Autowired BillService billing;
    @Autowired BillQueries queries;
    @Autowired GepgSubmissionWorker submissions;
    @Autowired SimulatorGepgGateway simulator;
    @Autowired SimulatorController gepg;
    @Autowired Actors actors;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void suspensePaymentIsMovedToTheRightBill() {
        String tx = "SUSP-" + UUID.randomUUID();
        assertEquals("SUSPENSE", pay("990000000777", "45000.00", tx));
        UUID payment = corrections.paymentByTransaction(tx).orElseThrow();
        UUID caseId = caseFor(tx);
        UUID bill = issuedBill();

        UUID id = UUID.fromString(corrections.request(maker(), CorrectionService.Kind.ALLOCATE, payment, bill, "Client quoted an old number", caseId).correctionId());
        assertEquals(403, assertThrows(ApiException.class, () -> corrections.decide(maker(), id, "APPROVED", null)).status());
        assertEquals("Applied", corrections.decide(approver(), id, "APPROVED", "Bank slip checked").status());

        assertEquals("Paid", queries.view(bill).orElseThrow().collectionState());
        assertTrue(corrections.unallocated().stream().noneMatch(p -> p.paymentId().equals(payment.toString())));
        assertEquals("CLOSED", jdbc.sql("SELECT status FROM exception_case WHERE id = :c").param("c", caseId).query(String.class).single());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM financial_adjustment WHERE original_record_id = :p AND type = 'REALLOCATION'")
                .param("p", payment).query(Long.class).single().longValue());
        assertNoViolations(bill);
    }

    @Test
    void wrongBillIsReversedAndMoneyReallocated() {
        UUID wrong = issuedBill();
        String tx = "REV-" + UUID.randomUUID();
        assertEquals("ACCEPTED", pay(queries.view(wrong).orElseThrow().controlNumber(), "45000.00", tx));
        UUID payment = corrections.paymentByTransaction(tx).orElseThrow();

        UUID rev = UUID.fromString(corrections.request(maker(), CorrectionService.Kind.REVERSE, payment, wrong, "Paid on the wrong bill", null).correctionId());
        corrections.decide(approver(), rev, "APPROVED", null);

        assertEquals("Unpaid", queries.view(wrong).orElseThrow().collectionState());
        // The original allocation is kept; a reversal row is appended
        assertEquals(List.of("ALLOCATION", "REVERSAL"), jdbc.sql("SELECT kind FROM payment_allocation WHERE payment_id = :p ORDER BY created_at, kind")
                .param("p", payment).query(String.class).list());
        assertTrue(corrections.unallocated().stream().anyMatch(p -> p.paymentId().equals(payment.toString()) && p.unallocated().equals("45000.00")));

        UUID right = issuedBill();
        UUID move = UUID.fromString(corrections.request(maker(), CorrectionService.Kind.ALLOCATE, payment, right, "Correct bill", null).correctionId());
        corrections.decide(approver(), move, "APPROVED", null);
        assertEquals("Paid", queries.view(right).orElseThrow().collectionState());
        assertNoViolations(wrong);
        assertNoViolations(right);
    }

    @Test
    void reversalAfterReleaseAsksThePlatformToReview() {
        UUID bill = issuedBill();
        String tx = "REL-" + UUID.randomUUID();
        pay(queries.view(bill).orElseThrow().controlNumber(), "45000.00", tx);
        jdbc.sql("UPDATE bill SET release_state = 'Released' WHERE id = :b").param("b", bill).update();   // service delivered
        UUID payment = corrections.paymentByTransaction(tx).orElseThrow();

        UUID rev = UUID.fromString(corrections.request(maker(), CorrectionService.Kind.REVERSE, payment, bill, "Bank recalled the payment", null).correctionId());
        corrections.decide(approver(), rev, "APPROVED", null);

        assertEquals(1L, jdbc.sql("SELECT count(*) FROM exception_case WHERE bill_id = :b AND type = 'REVERSAL_AFTER_RELEASE'")
                .param("b", bill).query(Long.class).single().longValue());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM outbox_event WHERE aggregate_id = :b AND event_type = 'payment.reversed'")
                .param("b", bill).query(Long.class).single().longValue());
    }

    @Test
    void unallocatedMoneyCaseNeedsEvidenceToClose() {
        String tx = "EVD-" + UUID.randomUUID();
        pay("990000000778", "1000.00", tx);
        UUID caseId = caseFor(tx);
        Actor m = maker();
        caseWork.take(m, caseId);
        assertEquals(422, assertThrows(ApiException.class, () -> caseWork.close(m, caseId, "Returned to the payer's bank", null)).status());
        caseWork.close(m, caseId, "Returned to the payer's bank", "CRDB-RETURN-2026-0042");
        assertEquals("CLOSED", jdbc.sql("SELECT status FROM exception_case WHERE id = :c").param("c", caseId).query(String.class).single());
    }

    @Test
    void oneOpenCorrectionPerPayment() {
        String tx = "ONE-" + UUID.randomUUID();
        pay("990000000779", "45000.00", tx);
        UUID payment = corrections.paymentByTransaction(tx).orElseThrow();
        UUID a = issuedBill();
        UUID b = issuedBill();
        corrections.request(maker(), CorrectionService.Kind.ALLOCATE, payment, a, "First officer", null);
        assertEquals(409, assertThrows(ApiException.class,
                () -> corrections.request(maker(), CorrectionService.Kind.ALLOCATE, payment, b, "Second officer", null)).status());
    }

    // ---- helpers ----

    private UUID caseFor(String tx) {
        return jdbc.sql("SELECT id FROM exception_case WHERE type = 'UNMATCHED_PAYMENT' AND reference = :tx")
                .param("tx", tx).query(UUID.class).single();
    }

    private UUID issuedBill() {
        Actor lims = as("lims", "PLATFORM");
        UUID id = billing.create(lims, UUID.randomUUID().toString(), new CreateBillRequest("COR-" + UUID.randomUUID().toString().substring(0, 8),
                new CreateBillRequest.Payer("CUST-COR", "Correction Test Client", "255700000003", null), "TZS", "Correction test", 30,
                List.of(new CreateBillRequest.Line("LIMS-CHEM-001", 1, BigDecimal.ONE)), null, "en")).billId();
        submissions.runOnce();
        simulator.issuePendingControlNumbers();
        return id;
    }

    private String pay(String controlNumber, String amount, String tx) {
        return String.valueOf(gepg.pay(new SimulatorController.SimulatedPayment(controlNumber, new BigDecimal(amount), null, null, tx,
                null, null, false)).get("result"));
    }

    private void assertNoViolations(UUID bill) {
        assertEquals(List.of(), jdbc.sql("SELECT rule FROM v_invariant_violations WHERE bill_id = :b").param("b", bill).query(String.class).list());
    }

    private Actor maker() {
        return as("finance.maker", "BILLING_OFFICER");
    }

    private Actor approver() {
        return as("finance.approver", "BILL_APPROVER");
    }

    private Actor as(String user, String... roles) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, "n/a",
                java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return actors.current();
    }
}
