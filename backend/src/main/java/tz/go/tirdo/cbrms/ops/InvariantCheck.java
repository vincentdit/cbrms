package tz.go.tirdo.cbrms.ops;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.go.tirdo.cbrms.audit.AuditService;
import tz.go.tirdo.cbrms.cases.CaseService;

/**
 * Nightly consistency check (invariants I1 to I7 and ledger drift). Each violation opens one INVARIANT_VIOLATION case
 * per bill and rule, so Finance and ICTT see it even if nobody is watching the alerts.
 */
@Component
public class InvariantCheck {
    private static final Logger log = LoggerFactory.getLogger(InvariantCheck.class);

    public record Result(int violations, int casesOpened) {}

    private final JdbcClient jdbc;
    private final CaseService cases;
    private final AuditService audit;

    public InvariantCheck(JdbcClient jdbc, CaseService cases, AuditService audit) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.audit = audit;
    }

    @Scheduled(cron = "${cbrms.ops.invariant-cron:0 15 2 * * *}", zone = "Africa/Dar_es_Salaam")
    public void nightly() {
        run("cbrms-system");
    }

    public Result run(String actor) {
        record Violation(String rule, UUID billId, String detail) {}
        List<Violation> found = jdbc.sql("SELECT rule, bill_id, detail FROM v_invariant_violations")
                .query((rs, n) -> new Violation(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3))).list();
        int opened = 0;
        for (Violation v : found) {
            if (cases.openOnceForBill("INVARIANT_VIOLATION", v.billId(), v.rule() + ": " + v.detail())) {
                opened++;
            }
        }
        audit.record(actor, "INVARIANT_CHECK", "invariants", null, Map.of("violations", found.size(), "casesOpened", opened));
        if (!found.isEmpty()) {
            log.error("Invariant check found {} violation(s); {} new case(s) opened", found.size(), opened);
        }
        return new Result(found.size(), opened);
    }
}
