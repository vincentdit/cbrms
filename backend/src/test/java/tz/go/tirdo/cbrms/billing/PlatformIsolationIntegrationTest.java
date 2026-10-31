package tz.go.tirdo.cbrms.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import tz.go.tirdo.cbrms.admin.AdminController;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;

/** CIAP, TeLTP and ISICMS share CBRMS with LIMS without seeing or billing each other's work (FR26). */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CBRMS_TEST_DB_URL", matches = ".+")
class PlatformIsolationIntegrationTest {

    @Autowired BillService billing;
    @Autowired BillController bills;
    @Autowired BillChangeService changes;
    @Autowired AdminController admin;
    @Autowired Actors actors;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void eachPlatformBillsItsOwnServices() {
        assertEquals("ciap", create(as("ciap"), "CIAP-ADV-001", ref()).sourceSystem());
        assertEquals("teltp", create(as("teltp"), "TELTP-CRS-001", ref()).sourceSystem());
        assertEquals("isicms", create(as("isicms"), "ISICMS-INC-001", ref()).sourceSystem());
        // Keycloak service-account identities resolve to the same platform
        assertEquals("ciap", create(as("service-account-ciap"), "CIAP-ADV-001", ref()).sourceSystem());
    }

    @Test
    void platformCannotBillAnotherPlatformsService() {
        ApiException e = assertThrows(ApiException.class, () -> create(as("ciap"), "LIMS-CHEM-001", ref()));
        assertEquals(403, e.status());
        assertEquals(403, assertThrows(ApiException.class, () -> create(as("teltp"), "ISICMS-INC-001", ref())).status());
    }

    @Test
    void platformCannotSeeOrChangeAnotherPlatformsBill() {
        BillView lims = create(as("lims"), "LIMS-CHEM-001", ref());
        UUID id = UUID.fromString(lims.billId());

        as("ciap");
        assertEquals(404, assertThrows(ApiException.class, () -> bills.get(id)).status(), "looks the same as a missing bill");
        Actor ciap = as("ciap");
        assertEquals(404, assertThrows(ApiException.class, () -> changes.requestCancellation(ciap, id, "Not mine")).status());

        as("lims");
        assertEquals(lims.billId(), bills.get(id).billId());
    }

    @Test
    void sameReferenceOnTwoPlatformsIsTwoLiabilities() {
        String shared = ref();
        BillView a = create(as("ciap"), "CIAP-ADV-001", shared);
        BillView b = create(as("isicms"), "ISICMS-INC-001", shared);
        assertNotEquals(a.billId(), b.billId());
    }

    @Test
    void financeSeesAllPlatforms() {
        as("finance.approver", "BILL_APPROVER");
        List<AdminController.PlatformRow> list = admin.platforms();
        List<String> codes = list.stream().map(AdminController.PlatformRow::code).toList();
        assertTrue(codes.containsAll(List.of("lims", "ciap", "teltp", "isicms")));
        AdminController.PlatformRow ciap = list.stream().filter(p -> p.code().equals("ciap")).findFirst().orElseThrow();
        assertTrue(ciap.services().containsAll(List.of("CIAP-ADV-001", "CIAP-REV-002", "CIAP-CONS-003")));
        assertFalse(ciap.services().contains("LIMS-CHEM-001"));
    }

    private BillView create(Actor actor, String service, String ref) {
        CreateBillRequest req = new CreateBillRequest(ref,
                new CreateBillRequest.Payer("CUST-" + UUID.randomUUID().toString().substring(0, 6), "Test Client Ltd", "255700000000", null),
                "TZS", "Platform isolation " + ref, 30, List.of(new CreateBillRequest.Line(service, 1, BigDecimal.ONE)), null, "en");
        UUID id = billing.create(actor, UUID.randomUUID().toString(), req).billId();
        return bills.get(id);
    }

    private Actor as(String user, String... roles) {
        String[] r = roles.length == 0 ? new String[] {"PLATFORM"} : roles;
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, "n/a",
                java.util.Arrays.stream(r).map(x -> new SimpleGrantedAuthority("ROLE_" + x)).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return actors.current();
    }

    private static String ref() {
        return "REF-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
