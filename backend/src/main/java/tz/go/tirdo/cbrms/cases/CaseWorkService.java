package tz.go.tirdo.cbrms.cases;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.cbrms.audit.AuditService;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;

/**
 * Working exception cases (FR21): take ownership, then close with a resolution. Closing never moves money; money is moved
 * only by an approved correction. A case about unallocated money can be closed only with evidence (for example a bank
 * return reference), so it cannot simply disappear from the register.
 */
@Service
public class CaseWorkService {

    private final JdbcClient jdbc;
    private final AuditService audit;

    public CaseWorkService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public void take(Actor officer, UUID caseId) {
        requireOfficer(officer);
        int rows = jdbc.sql("UPDATE exception_case SET owner_id = :me, status = 'IN_PROGRESS' WHERE id = :id AND status <> 'CLOSED'")
                .param("me", officer.userId()).param("id", caseId).update();
        if (rows == 0) {
            throw ApiException.conflict("CBR-STA-001", "This case is closed or does not exist");
        }
        audit.record(officer.subject(), "CASE_TAKEN", "case:" + caseId, null, null);
    }

    @Transactional
    public void close(Actor officer, UUID caseId, String resolution, String evidenceRef) {
        requireOfficer(officer);
        if (resolution == null || resolution.trim().length() < 10 || resolution.length() > 500) {
            throw ApiException.validation("resolution", "describe how it was resolved (10 to 500 characters)");
        }
        record Case(String type, String status, String reference) {}
        Case c = jdbc.sql("SELECT type, status, reference FROM exception_case WHERE id = :id FOR UPDATE")
                .param("id", caseId)
                .query((rs, n) -> new Case(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional()
                .orElseThrow(() -> ApiException.notFound("Case not found"));
        if ("CLOSED".equals(c.status())) {
            throw ApiException.conflict("CBR-STA-001", "This case is already closed");
        }
        if ("UNMATCHED_PAYMENT".equals(c.type()) && c.reference() != null && (evidenceRef == null || evidenceRef.isBlank())) {
            BigDecimal unallocated = jdbc.sql("""
                    SELECT coalesce(max(u.unallocated), 0)
                      FROM payment p JOIN v_payment_unallocated u ON u.payment_id = p.id
                     WHERE p.external_transaction_id = :tx
                    """)
                .param("tx", c.reference()).query(BigDecimal.class).single();
            if (unallocated.signum() > 0) {
                throw ApiException.validation("evidenceRef", "TZS " + unallocated.toPlainString()
                        + " of this payment is still unallocated. Move it to a bill, or give the evidence reference for how it was returned.");
            }
        }
        jdbc.sql("""
                UPDATE exception_case SET status = 'CLOSED', closed_at = now(), closure_reason = :why, evidence_ref = :ev,
                       owner_id = coalesce(owner_id, :me)
                 WHERE id = :id
                """)
            .param("why", resolution.trim()).param("ev", evidenceRef == null || evidenceRef.isBlank() ? null : evidenceRef.trim())
            .param("me", officer.userId()).param("id", caseId)
            .update();
        audit.record(officer.subject(), "CASE_CLOSED", "case:" + caseId, resolution, null);
    }

    private static void requireOfficer(Actor a) {
        if (!a.finance()) {
            throw ApiException.forbidden("CBR-AUT-006", "Cases are worked by Finance");
        }
    }
}
