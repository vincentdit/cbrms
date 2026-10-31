package tz.go.tirdo.cbrms.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
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
import tz.go.tirdo.cbrms.billing.BillService;
import tz.go.tirdo.cbrms.common.ApiException;

/** FR28: actor, object, reason and time are searchable; exports are themselves audited. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CBRMS_TEST_DB_URL", matches = ".+")
class AuditSearchIntegrationTest {

    @Autowired AuditSearchController search;
    @Autowired AuditService audit;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void findsEventsByActorActionAndRecord() {
        String ref = "TEST-" + UUID.randomUUID();
        audit.record("finance.maker", "CASE_CLOSED", "case:" + ref, "Returned to payer", null);
        audit.record("finance.approver", "CASE_TAKEN", "case:" + ref, null, null);
        as("finance.approver", "BILL_APPROVER");
        LocalDate today = LocalDate.now(BillService.BUSINESS_ZONE);

        AuditSearchController.AuditPage byRecord = search.search(today, today, "", "", ref, "", 0, 50);
        assertEquals(2L, byRecord.total(), "a bare ID finds every record type ending with it");

        AuditSearchController.AuditPage byActor = search.search(today, today, "finance.maker", "CASE_CLOSED", "case:" + ref, "", 0, 50);
        assertEquals(1L, byActor.total());
        assertEquals("Returned to payer", byActor.items().get(0).reason());
        assertTrue(byActor.items().get(0).actorName().contains("Asha"), "the person's name is shown, not just the login");
    }

    @Test
    void exportIsAuditedAndRangeIsBounded() {
        as("finance.approver", "BILL_APPROVER");
        LocalDate today = LocalDate.now(BillService.BUSINESS_ZONE);
        String body = search.export(today, today, "", "", "", "").getBody();
        assertTrue(body.startsWith("﻿CBRMS audit trail"));
        assertTrue(search.search(today, today, "finance.approver", "AUDIT_EXPORTED", "", "", 0, 10).total() >= 1);
        assertEquals(422, assertThrows(ApiException.class, () -> search.search(today.minusDays(400), today, "", "", "", "", 0, 10)).status());
    }

    @Test
    void platformsCannotReadTheTrail() {
        as("lims", "PLATFORM");
        assertEquals(403, assertThrows(ApiException.class, () -> search.actions()).status());
    }

    private void as(String user, String... roles) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, "n/a",
                java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
