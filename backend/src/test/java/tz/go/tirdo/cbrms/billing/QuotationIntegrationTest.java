package tz.go.tirdo.cbrms.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;

/** CIAP consultancy priced from an approved quotation, billed milestone by milestone. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CBRMS_TEST_DB_URL", matches = ".+")
class QuotationIntegrationTest {

    static final String SERVICE = "CIAP-CONS-003";

    @Autowired QuotationService quotations;
    @Autowired BillService billing;
    @Autowired BillQueries queries;
    @Autowired Actors actors;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void approvedQuotationIsBilledByMilestone() {
        String quote = quote();
        QuotationService.QuotationView q = submit(quote, "CUST-Q1", "4000000.00", "6000000.00");
        assertEquals("Requested", q.status());
        assertEquals("10000000.00", q.total());

        // The platform cannot bill before Finance approves
        assertEquals(422, assertThrows(ApiException.class, () -> bill(quote, "CUST-Q1", 1)).status());

        approve(q);
        UUID first = bill(quote, "CUST-Q1", 1);
        BillView v = queries.view(first).orElseThrow();
        assertEquals("4000000.00", v.total(), "price comes from the milestone, not the client");
        assertEquals("Approved", v.preparationState(), "the quotation approval stands in for bill approval");
        assertTrue(v.lines().get(0).description().contains("Inception report"));

        // Re-sending the same liability returns the same bill (BR03), so platform retries are safe
        assertEquals(first, bill(quote, "CUST-Q1", 1));
        // Billing the same milestone under another reference is refused; the next milestone is fine
        ApiException twice = assertThrows(ApiException.class, () -> bill(quote, "CUST-Q1", 1, "-DUP"));
        assertEquals(409, twice.status());
        UUID second = bill(quote, "CUST-Q1", 2);
        assertEquals("6000000.00", queries.view(second).orElseThrow().total());

        QuotationService.QuotationView after = quotations.view(UUID.fromString(q.quotationId()));
        assertEquals("10000000.00", after.billed());
        assertEquals(first.toString(), after.milestones().get(0).billId());
    }

    @Test
    void submitterCannotApproveOwnQuotation() {
        QuotationService.QuotationView q = submit(quote(), "CUST-Q2", "1500000.00");
        Actor ciap = as("ciap", "PLATFORM");
        assertEquals(403, assertThrows(ApiException.class,
                () -> quotations.decide(ciap, UUID.fromString(q.quotationId()), "APPROVED", null)).status());
    }

    @Test
    void quotationTermsAreChecked() {
        String quote = quote();
        QuotationService.QuotationView q = submit(quote, "CUST-Q3", "2000000.00");
        approve(q);
        // different payer
        assertEquals(422, assertThrows(ApiException.class, () -> bill(quote, "CUST-OTHER", 1)).status());
        // no such milestone
        assertEquals(422, assertThrows(ApiException.class, () -> bill(quote, "CUST-Q3", 5)).status());
        // a quoted service without a quotation
        Actor ciap = as("ciap", "PLATFORM");
        assertEquals(422, assertThrows(ApiException.class, () -> billing.create(ciap, key(),
                request(null, "CUST-Q3", new CreateBillRequest.Line(SERVICE, 1, BigDecimal.ONE)))).status());
        // a catalogue service with a quotation reference
        assertEquals(422, assertThrows(ApiException.class, () -> billing.create(ciap, key(),
                request(null, "CUST-Q3", new CreateBillRequest.Line("CIAP-ADV-001", 1, BigDecimal.ONE, quote, 1)))).status());
    }

    @Test
    void sameReferenceIsIdempotentButNewTermsNeedNewReference() {
        String quote = quote();
        QuotationService.QuotationView a = submit(quote, "CUST-Q4", "500000.00");
        QuotationService.QuotationView b = submit(quote, "CUST-Q4", "500000.00");
        assertEquals(a.quotationId(), b.quotationId());
        assertEquals(409, assertThrows(ApiException.class, () -> submit(quote, "CUST-Q4", "750000.00")).status());
    }

    @Test
    void otherPlatformsCannotUseTheQuotation() {
        String quote = quote();
        approve(submit(quote, "CUST-Q5", "800000.00"));
        Actor isicms = as("isicms", "PLATFORM");
        // ISICMS is not mapped to the quoted service at all
        assertEquals(403, assertThrows(ApiException.class, () -> billing.create(isicms, key(),
                request(quote, "CUST-Q5", new CreateBillRequest.Line(SERVICE, 1, BigDecimal.ONE, quote, 1)))).status());
    }

    @Test
    void cancelledMilestoneBillFreesTheMilestone() {
        String quote = quote();
        QuotationService.QuotationView q = submit(quote, "CUST-Q6", "300000.00");
        approve(q);
        UUID first = bill(quote, "CUST-Q6", 1);
        // Simulate GePG having cancelled the bill
        jdbc.sql("UPDATE bill SET registration_state = 'Cancelled' WHERE id = :id").param("id", first).update();
        UUID again = bill(quote, "CUST-Q6", 1, "-R1");
        assertFalse(again.equals(first), "after GePG cancels a milestone bill, the milestone can be billed again");
    }

    // ---- helpers ----

    private QuotationService.QuotationView submit(String reference, String payerRef, String... amounts) {
        Actor ciap = as("ciap", "PLATFORM");
        List<QuotationService.MilestoneRequest> ms = new java.util.ArrayList<>();
        String[] names = {"Inception report", "Final report", "Training", "Handover"};
        for (int i = 0; i < amounts.length; i++) {
            ms.add(new QuotationService.MilestoneRequest(names[i], new BigDecimal(amounts[i])));
        }
        return quotations.submit(ciap, new QuotationService.SubmitRequest(reference, payer(payerRef), SERVICE,
                "Energy audit for Tanga Cement", LocalDate.now(BillService.BUSINESS_ZONE).plusMonths(6), "en", ms));
    }

    private void approve(QuotationService.QuotationView q) {
        quotations.decide(as("finance.approver", "BILL_APPROVER"), UUID.fromString(q.quotationId()), "APPROVED", "Price checked");
    }

    private UUID bill(String quote, String payerRef, int milestone) {
        return bill(quote, payerRef, milestone, "");
    }

    private UUID bill(String quote, String payerRef, int milestone, String suffix) {
        Actor ciap = as("ciap", "PLATFORM");
        return billing.create(ciap, key(), request(quote + "-M" + milestone + suffix, payerRef,
                new CreateBillRequest.Line(SERVICE, 1, BigDecimal.ONE, quote, milestone))).billId();
    }

    private static CreateBillRequest request(String ref, String payerRef, CreateBillRequest.Line line) {
        return new CreateBillRequest(ref == null ? "CIAP-" + UUID.randomUUID().toString().substring(0, 8) : ref, payer(payerRef), "TZS",
                "Consultancy milestone", 30, List.of(line), null, "en");
    }

    private static CreateBillRequest.Payer payer(String ref) {
        return new CreateBillRequest.Payer(ref, "Tanga Cement PLC", "255700000001", "finance@example.invalid");
    }

    private Actor as(String user, String... roles) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, "n/a",
                java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return actors.current();
    }

    private static String quote() {
        return "Q-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
