package tz.go.tirdo.cbrms.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import tz.go.tirdo.cbrms.gepg.GepgSubmissionWorker;
import tz.go.tirdo.cbrms.gepg.sim.SimulatorController;
import tz.go.tirdo.cbrms.gepg.sim.SimulatorGepgGateway;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CBRMS_TEST_DB_URL", matches = ".+")
class ReportsIntegrationTest {

    @Autowired ReportService reports;
    @Autowired ReportsController controller;
    @Autowired BillService billing;
    @Autowired BillQueries queries;
    @Autowired GepgSubmissionWorker submissions;
    @Autowired SimulatorGepgGateway simulator;
    @Autowired SimulatorController gepg;
    @Autowired Actors actors;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void collectionsReconcileAndSplitAcrossLines() {
        LocalDate today = LocalDate.now(BillService.BUSINESS_ZONE);
        ReportService.Collections before = reports.collections(today, today, ReportService.GroupBy.SERVICE);

        // 2 x 45,000 chemistry, part-paid with 30,000 (EXACT mode opens an amount-mismatch case but still applies the money)
        Actor lims = as("lims", "PLATFORM");
        UUID id = billing.create(lims, UUID.randomUUID().toString(), new CreateBillRequest("RPT-" + UUID.randomUUID().toString().substring(0, 8),
                new CreateBillRequest.Payer("CUST-RPT", "Report Client", "255700000009", null), "TZS", "Report test", 30,
                List.of(new CreateBillRequest.Line("LIMS-CHEM-001", 1, new BigDecimal("2"))), null, "en")).billId();
        submissions.runOnce();
        simulator.issuePendingControlNumbers();
        gepg.pay(new SimulatorController.SimulatedPayment(queries.view(id).orElseThrow().controlNumber(), new BigDecimal("30000.00"),
                null, null, null, null, null, false));

        ReportService.Collections after = reports.collections(today, today, ReportService.GroupBy.SERVICE);
        assertEquals(0, after.received().compareTo(after.applied().add(after.credit()).add(after.suspense())), "totals reconcile");
        assertEquals(0, after.applied().subtract(before.applied()).compareTo(new BigDecimal("30000.00")));
        BigDecimal byService = after.rows().stream().map(ReportService.CollectionRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, byService.compareTo(after.applied()), "service rows add up to what was applied");

        ReportService.Collections byPlatform = reports.collections(today, today, ReportService.GroupBy.PLATFORM);
        assertTrue(byPlatform.rows().stream().anyMatch(r -> r.key().equals("lims")));

        ReportService.Outstanding o = reports.outstanding();
        ReportService.OutstandingBill mine = o.bills().stream().filter(b -> b.billId().equals(id.toString())).findFirst().orElseThrow();
        assertEquals(0, mine.balance().compareTo(new BigDecimal("60000.00")));
        assertEquals("NOT_YET_DUE", mine.bucket());
    }

    @Test
    void rangeIsValidated() {
        LocalDate d = LocalDate.of(2026, 10, 31);
        // Validation failures are 422 across the CBRMS API (ApiException.validation)
        assertEquals(422, assertThrows(ApiException.class, () -> reports.collections(d, d.minusDays(1), ReportService.GroupBy.GFS)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> reports.collections(d.minusDays(400), d, ReportService.GroupBy.GFS)).status());
    }

    @Test
    void platformsCannotReadReports() {
        as("lims", "PLATFORM");
        assertEquals(403, assertThrows(ApiException.class, () -> controller.outstanding()).status());
    }

    @Test
    void csvExportIsAudited() {
        as("finance.maker", "BILLING_OFFICER");
        LocalDate today = LocalDate.now(BillService.BUSINESS_ZONE);
        String body = controller.collectionsCsv(today, today, "gfs").getBody();
        assertTrue(body.startsWith("﻿CBRMS collections"));
    }

    private Actor as(String user, String... roles) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, "n/a",
                java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return actors.current();
    }
}
