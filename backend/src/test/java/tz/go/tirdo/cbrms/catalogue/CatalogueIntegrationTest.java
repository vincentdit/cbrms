package tz.go.tirdo.cbrms.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import tz.go.tirdo.cbrms.billing.BillQueries;
import tz.go.tirdo.cbrms.billing.BillService;
import tz.go.tirdo.cbrms.billing.CreateBillRequest;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;

/** Catalogue maintenance under maker-checker (FR01, AT01). Each test makes its own service so runs never collide. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CBRMS_TEST_DB_URL", matches = ".+")
class CatalogueIntegrationTest {

    @Autowired CatalogueService catalogue;
    @Autowired BillService billing;
    @Autowired BillQueries queries;
    @Autowired Actors actors;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void newServiceNeedsSecondOfficerThenCanBeBilled() {
        String code = newCode();
        CatalogueService.ChangeView change = catalogue.propose(maker(), newService(code, "1250.40", List.of("lims")));
        assertEquals("Requested", change.status());
        assertThrows(ApiException.class, () -> billing.create(as("lims", "PLATFORM"), key(), bill(code, 1)), "not billable before approval");

        UUID id = UUID.fromString(change.changeId());
        assertEquals(403, assertThrows(ApiException.class, () -> catalogue.decide(maker(), id, "APPROVED", null)).status());
        assertEquals("Applied", catalogue.decide(approver(), id, "APPROVED", "Board minute checked").status());

        // 3 x 1,250.40 = 3,751.20, rounded to whole shillings by the approved rule
        UUID billId = billing.create(as("lims", "PLATFORM"), key(), bill(code, 3)).billId();
        assertEquals("3751.00", queries.view(billId).orElseThrow().total());

        Actor lims = as("lims", "PLATFORM");
        assertTrue(catalogue.forPlatform(lims.sourceSystemId()).stream().anyMatch(s -> s.serviceCode().equals(code)
                && Integer.valueOf(1).equals(s.currentVersion())));
    }

    @Test
    void newPriceIsScheduledAndNeverBackdated() {
        String code = approvedService("5000.00");
        LocalDate today = LocalDate.now(BillService.BUSINESS_ZONE);

        ApiException backdated = assertThrows(ApiException.class, () -> catalogue.propose(maker(), newTariff(code, "6000.00", today.minusDays(1))));
        assertEquals(422, backdated.status());

        UUID id = UUID.fromString(catalogue.propose(maker(), newTariff(code, "6000.00", today.plusDays(1))).changeId());
        catalogue.decide(approver(), id, "APPROVED", null);

        CatalogueService.ServiceDetail d = catalogue.detail(code);
        assertEquals("5000.00", d.service().currentPrice(), "today's bills keep today's price");
        assertEquals("6000.00", d.service().nextPrice());
        assertEquals(today.plusDays(1).toString(), d.service().nextFrom());
        CatalogueService.TariffRow v1 = d.tariffs().stream().filter(t -> t.version() == 1).findFirst().orElseThrow();
        assertEquals(today.plusDays(1).toString(), v1.to(), "version 1 now ends the day the new price starts");

        // A bill issued today on version 1 is unaffected (AT01)
        UUID billId = billing.create(as("lims", "PLATFORM"), key(), bill(code, 1)).billId();
        assertEquals("5000.00", queries.view(billId).orElseThrow().total());
    }

    @Test
    void conflictingStartDateIsRefused() {
        String code = approvedService("100.00");
        LocalDate today = LocalDate.now(BillService.BUSINESS_ZONE);
        catalogue.decide(approver(), UUID.fromString(catalogue.propose(maker(), newTariff(code, "120.00", today.plusDays(10))).changeId()),
                "APPROVED", null);
        // A version starting earlier than an already approved future version would overlap it
        UUID early = UUID.fromString(catalogue.propose(maker(), newTariff(code, "110.00", today.plusDays(5))).changeId());
        assertEquals(409, assertThrows(ApiException.class, () -> catalogue.decide(approver(), early, "APPROVED", null)).status());
    }

    @Test
    void onlyOneOpenChangePerService() {
        String code = approvedService("100.00");
        LocalDate tomorrow = LocalDate.now(BillService.BUSINESS_ZONE).plusDays(1);
        catalogue.propose(maker(), newTariff(code, "150.00", tomorrow));
        assertEquals(409, assertThrows(ApiException.class, () -> catalogue.propose(maker(), newTariff(code, "160.00", tomorrow))).status());
    }

    @Test
    void deactivatedServiceCannotBeBilledAndAccessCanBeGranted() {
        String code = approvedService("700.00");
        assertEquals(403, assertThrows(ApiException.class, () -> billing.create(as("ciap", "PLATFORM"), key(), bill(code, 1))).status());

        UUID grant = UUID.fromString(catalogue.propose(maker(), update(code, null, List.of("lims", "ciap"))).changeId());
        catalogue.decide(approver(), grant, "APPROVED", null);
        assertTrue(catalogue.detail(code).service().platforms().contains("ciap"));
        billing.create(as("ciap", "PLATFORM"), key(), bill(code, 1));

        UUID off = UUID.fromString(catalogue.propose(maker(), update(code, "INACTIVE", null)).changeId());
        catalogue.decide(approver(), off, "APPROVED", null);
        assertEquals(422, assertThrows(ApiException.class, () -> billing.create(as("lims", "PLATFORM"), key(), bill(code, 1))).status());
    }

    @Test
    void rejectionNeedsReasonAndChangesNothing() {
        String code = approvedService("900.00");
        UUID id = UUID.fromString(catalogue.propose(maker(), newTariff(code, "50.00", LocalDate.now(BillService.BUSINESS_ZONE).plusDays(1))).changeId());
        assertEquals(422, assertThrows(ApiException.class, () -> catalogue.decide(approver(), id, "REJECTED", " ")).status());
        assertEquals("Rejected", catalogue.decide(approver(), id, "REJECTED", "Price not approved by DG").status());
        assertNull(catalogue.detail(code).service().nextPrice());
    }

    // ---- helpers ----

    private String approvedService(String price) {
        String code = newCode();
        UUID id = UUID.fromString(catalogue.propose(maker(), newService(code, price, List.of("lims"))).changeId());
        catalogue.decide(approver(), id, "APPROVED", null);
        return code;
    }

    private static CatalogueService.Proposal newService(String code, String price, List<String> platforms) {
        return new CatalogueService.Proposal(CatalogueService.Kind.NEW_SERVICE, code, "New laboratory test", "DG-APPROVAL-TEST",
                "Test service " + code, "Huduma ya majaribio", "LAB", "sample", 0, false, "TARIFF", null, platforms,
                new BigDecimal(price), "EXACT", "WHOLE_SHILLING_HALF_UP", 30, "DEV-GFS-0001", "DEV-ACC-0001",
                LocalDate.now(BillService.BUSINESS_ZONE), null);
    }

    private static CatalogueService.Proposal newTariff(String code, String price, LocalDate from) {
        return new CatalogueService.Proposal(CatalogueService.Kind.NEW_TARIFF, code, "Annual price review", "DG-APPROVAL-TEST-2",
                null, null, null, null, null, null, null, null, null,
                new BigDecimal(price), "EXACT", "WHOLE_SHILLING_HALF_UP", 30, null, null, from, null);
    }

    private static CatalogueService.Proposal update(String code, String status, List<String> platforms) {
        return new CatalogueService.Proposal(CatalogueService.Kind.SERVICE_UPDATE, code, "Service settings", null,
                null, null, null, null, null, null, null, status, platforms,
                null, null, null, null, null, null, null, null);
    }

    private static CreateBillRequest bill(String code, int quantity) {
        return new CreateBillRequest("CAT-" + UUID.randomUUID().toString().substring(0, 8),
                new CreateBillRequest.Payer("CUST-CAT", "Catalogue Test Client", "255700000002", null), "TZS", "Catalogue test", 30,
                List.of(new CreateBillRequest.Line(code, 1, new BigDecimal(quantity))), null, "en");
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

    private static String newCode() {
        return "TST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
