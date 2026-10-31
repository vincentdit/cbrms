package tz.go.tirdo.cbrms.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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
import tz.go.tirdo.cbrms.gepg.sim.SimulatorController;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;
import tz.go.tirdo.cbrms.refunds.RefundService;

/** Refunding money that never reached a bill, and the read-only auditor. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CBRMS_TEST_DB_URL", matches = ".+")
class PaymentRefundIntegrationTest {

    @Autowired RefundService refunds;
    @Autowired CorrectionService corrections;
    @Autowired SimulatorController gepg;
    @Autowired Actors actors;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void suspenseMoneyIsRefundedWithBankReference() {
        String tx = "SRF-" + UUID.randomUUID();
        pay("990000000880", "30000.00", tx);
        UUID payment = corrections.paymentByTransaction(tx).orElseThrow();
        UUID caseId = jdbc.sql("SELECT id FROM exception_case WHERE type = 'UNMATCHED_PAYMENT' AND reference = :tx")
                .param("tx", tx).query(UUID.class).single();

        Actor maker = as("finance.maker", "BILLING_OFFICER");
        assertEquals(422, assertThrows(ApiException.class, () -> refunds.requestFromPayment(maker, payment, new BigDecimal("30000.01"),
                "Too much", "Juma Hassan", "0754000111", caseId)).status(), "cannot refund more than is unallocated");
        UUID refund = refunds.requestFromPayment(maker, payment, new BigDecimal("30000.00"), "Payer asked for the money back",
                "Juma Hassan", "0754000111", caseId);

        // The money is reserved: it is no longer offered to be moved to a bill
        assertTrue(corrections.unallocated().stream().noneMatch(p -> p.paymentId().equals(payment.toString())));

        assertEquals(403, assertThrows(ApiException.class, () -> refunds.decide(maker, refund, "APPROVED", null)).status());
        refunds.decide(as("finance.approver", "BILL_APPROVER"), refund, "APPROVED", "Payer identity checked");
        refunds.recordExecution(as("finance.maker", "BILLING_OFFICER"), refund, "NMB-TRF-778812", null);

        assertEquals("CLOSED", jdbc.sql("SELECT status FROM exception_case WHERE id = :c").param("c", caseId).query(String.class).single());
        assertEquals("NMB-TRF-778812", jdbc.sql("SELECT evidence_ref FROM exception_case WHERE id = :c").param("c", caseId)
                .query(String.class).single());
    }

    @Test
    void rejectedRefundReleasesTheMoney() {
        String tx = "SRJ-" + UUID.randomUUID();
        pay("990000000881", "5000.00", tx);
        UUID payment = corrections.paymentByTransaction(tx).orElseThrow();
        UUID refund = refunds.requestFromPayment(as("finance.maker", "BILLING_OFFICER"), payment, new BigDecimal("5000.00"),
                "Payer asked", "Neema Juma", "0713000222", null);
        refunds.decide(as("finance.approver", "BILL_APPROVER"), refund, "REJECTED", "Payer could not be verified");
        assertTrue(corrections.unallocated().stream().anyMatch(p -> p.paymentId().equals(payment.toString()) && p.unallocated().equals("5000.00")));
    }

    @Test
    void auditorReadsLikeFinanceButIsNotAnOfficer() {
        Actor auditor = as("internal.auditor", "AUDITOR");
        assertTrue(auditor.finance(), "auditors can open the Finance views");
        assertEquals("HUMAN", auditor.kind());
    }

    private void pay(String controlNumber, String amount, String tx) {
        gepg.pay(new SimulatorController.SimulatedPayment(controlNumber, new BigDecimal(amount), null, null, tx, null, null, false));
    }

    private Actor as(String user, String... roles) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, "n/a",
                java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return actors.current();
    }
}
