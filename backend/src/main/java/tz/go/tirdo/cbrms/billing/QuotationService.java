package tz.go.tirdo.cbrms.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.cbrms.audit.AuditService;
import tz.go.tirdo.cbrms.catalogue.PricedTariff;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;

/**
 * Quotations for QUOTED services (CIAP consultancy). A platform submits the agreed price split into milestones,
 * a Finance bill approver approves it, and the platform then bills each milestone once by number.
 */
@Service
public class QuotationService {

    public record MilestoneRequest(String description, BigDecimal amount) {}

    public record SubmitRequest(String quoteReference, CreateBillRequest.Payer payer, String serviceCode, String description,
                                LocalDate validUntil, String language, List<MilestoneRequest> milestones) {}

    public record MilestoneView(int milestone, String description, String amount, String billId, String billState) {}

    public record QuotationView(String quotationId, String sourceSystem, String quoteReference, String serviceCode, String serviceName,
                                String payerReference, String payerName, String description, String total, String currency,
                                String validUntil, String status, String requestedBy, String createdAt, String decidedAt,
                                String billed, List<MilestoneView> milestones) {}

    /** Price of one bill line taken from an approved quotation milestone. */
    record PricedMilestone(UUID milestoneId, BigDecimal amount, String description) {}

    static final int MAX_MILESTONES = 24;

    private final JdbcClient jdbc;
    private final PayerRepository payers;
    private final AuditService audit;

    public QuotationService(JdbcClient jdbc, PayerRepository payers, AuditService audit) {
        this.jdbc = jdbc;
        this.payers = payers;
        this.audit = audit;
    }

    /** Idempotent per platform and quote reference: the same quotation sent twice returns the first. */
    @Transactional
    public QuotationView submit(Actor actor, SubmitRequest req) {
        if (!actor.isPlatform()) {
            throw ApiException.forbidden("CBR-AUT-005", "Quotations are submitted by registered source platforms");
        }
        List<ApiException.FieldProblem> problems = new ArrayList<>();
        List<MilestoneRequest> ms = req.milestones() == null ? List.of() : req.milestones();
        if (ms.isEmpty() || ms.size() > MAX_MILESTONES) {
            problems.add(new ApiException.FieldProblem("milestones", "give 1 to " + MAX_MILESTONES + " milestones"));
        }
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < ms.size(); i++) {
            MilestoneRequest m = ms.get(i);
            if (m == null || m.description() == null || m.description().isBlank() || m.description().length() > 200) {
                problems.add(new ApiException.FieldProblem("milestones[" + i + "].description", "1 to 200 characters"));
            }
            if (m == null || m.amount() == null || m.amount().signum() <= 0 || m.amount().scale() > 2
                    || m.amount().compareTo(new BigDecimal("1000000000000")) >= 0) {
                problems.add(new ApiException.FieldProblem("milestones[" + i + "].amount", "a positive TZS amount with at most 2 decimals"));
            } else {
                total = total.add(m.amount());
            }
        }
        LocalDate today = LocalDate.now(BillService.BUSINESS_ZONE);
        if (req.validUntil() == null || req.validUntil().isBefore(today) || req.validUntil().isAfter(today.plusYears(2))) {
            problems.add(new ApiException.FieldProblem("validUntil", "a date from today up to two years ahead"));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation("Validation failed", problems);
        }

        record Svc(UUID id, boolean quoted, boolean permitted) {}
        Svc svc = jdbc.sql("""
                SELECT s.id, s.pricing_basis = 'QUOTED',
                       EXISTS (SELECT 1 FROM source_system_service p WHERE p.source_system_id = :src AND p.service_id = s.id)
                  FROM service s WHERE s.code = :code AND s.status = 'ACTIVE'
                """)
            .param("src", actor.sourceSystemId()).param("code", req.serviceCode())
            .query((rs, n) -> new Svc(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getBoolean(3)))
            .optional()
            .orElseThrow(() -> ApiException.validation("serviceCode", "unknown service"));
        if (!svc.permitted()) {
            throw ApiException.forbidden("CBR-AUT-001", "Platform is not permitted to bill service " + req.serviceCode());
        }
        if (!svc.quoted()) {
            throw ApiException.validation("serviceCode", req.serviceCode() + " is priced from the catalogue, not by quotation");
        }

        Optional<UUID> existing = jdbc.sql("SELECT id FROM quotation WHERE source_system_id = :src AND quote_reference = :ref")
                .param("src", actor.sourceSystemId()).param("ref", req.quoteReference()).query(UUID.class).optional();
        if (existing.isPresent()) {
            QuotationView v = view(existing.get());
            if (v.total().equals(total.setScale(2).toPlainString()) && v.serviceCode().equals(req.serviceCode())
                    && v.payerReference().equals(req.payer().reference()) && v.milestones().size() == ms.size()) {
                return v;
            }
            throw ApiException.conflict("CBR-QUO-001", "Quotation " + req.quoteReference()
                    + " already exists with different terms. A revised price needs a new quote reference.");
        }

        UUID payerId = payers.upsert(actor.sourceSystemId(), req.payer(), req.language() == null ? "sw" : req.language());
        UUID id = jdbc.sql("""
                INSERT INTO quotation (source_system_id, quote_reference, service_id, payer_id, description, total, valid_until, requested_by)
                VALUES (:src, :ref, :svc, :payer, :description, :total, :validUntil, :by)
                RETURNING id
                """)
            .param("src", actor.sourceSystemId()).param("ref", req.quoteReference()).param("svc", svc.id())
            .param("payer", payerId).param("description", req.description()).param("total", total)
            .param("validUntil", req.validUntil()).param("by", actor.userId())
            .query(UUID.class).single();
        for (int i = 0; i < ms.size(); i++) {
            jdbc.sql("INSERT INTO quotation_milestone (quotation_id, milestone_no, description, amount) VALUES (:q, :no, :d, :a)")
                .param("q", id).param("no", i + 1).param("d", ms.get(i).description().trim()).param("a", ms.get(i).amount())
                .update();
        }
        UUID approvalId = jdbc.sql("INSERT INTO approval (subject_type, subject_id, maker_id) VALUES ('QUOTATION', :q, :maker) RETURNING id")
                .param("q", id).param("maker", actor.userId()).query(UUID.class).single();
        jdbc.sql("UPDATE quotation SET approval_id = :a WHERE id = :q").param("a", approvalId).param("q", id).update();
        audit.record(actor.subject(), "QUOTATION_SUBMITTED", "quotation:" + id, null,
                Map.of("reference", req.quoteReference(), "total", total, "milestones", ms.size()));
        return view(id);
    }

    @Transactional
    public QuotationView decide(Actor approver, UUID quotationId, String decision, String reason) {
        if (!approver.finance()) {
            throw ApiException.forbidden("CBR-AUT-006", "Only Finance can approve quotations");
        }
        record Open(String status, UUID approvalId, UUID makerId, String reference) {}
        Open q = jdbc.sql("""
                SELECT q.status, q.approval_id, a.maker_id, q.quote_reference
                  FROM quotation q LEFT JOIN approval a ON a.id = q.approval_id
                 WHERE q.id = :id FOR UPDATE OF q
                """)
            .param("id", quotationId)
            .query((rs, n) -> new Open(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4)))
            .optional()
            .orElseThrow(() -> ApiException.notFound("Quotation not found"));
        if (!"Requested".equals(q.status())) {
            throw ApiException.conflict("CBR-STA-001", "Quotation " + q.reference() + " is already " + q.status().toLowerCase());
        }
        if (approver.userId().equals(q.makerId())) {
            throw ApiException.forbidden("CBR-AUT-003", "The person who submitted a quotation cannot approve it");
        }
        boolean approve = "APPROVED".equals(decision);
        if (!approve && (reason == null || reason.isBlank())) {
            throw ApiException.validation("reason", "a reason is required to reject");
        }
        jdbc.sql("UPDATE approval SET approver_id = :by, decision = :d, reason = :r, decided_at = now() WHERE id = :id")
            .param("by", approver.userId()).param("d", approve ? "APPROVED" : "REJECTED").param("r", reason).param("id", q.approvalId())
            .update();
        jdbc.sql("UPDATE quotation SET status = :s, decided_at = now() WHERE id = :id")
            .param("s", approve ? "Approved" : "Rejected").param("id", quotationId).update();
        audit.record(approver.subject(), approve ? "QUOTATION_APPROVED" : "QUOTATION_REJECTED", "quotation:" + quotationId, reason, null);
        return view(quotationId);
    }

    /**
     * Prices a bill line for a QUOTED service. Locks the quotation so two bills cannot take the same milestone.
     * A milestone is free again only if its earlier bill was rejected, replaced or cancelled by GePG.
     */
    PricedMilestone priceMilestone(Actor actor, String payerReference, CreateBillRequest.Line line, PricedTariff tariff,
                                   String field, Set<UUID> takenInThisBill) {
        if (line.quotationReference() == null || line.quotationReference().isBlank() || line.milestone() == null) {
            throw ApiException.validation(field + ".quotationReference",
                    tariff.serviceCode() + " is priced by quotation: give quotationReference and milestone");
        }
        if (line.quantity().compareTo(BigDecimal.ONE) != 0) {
            throw ApiException.validation(field + ".quantity", "must be 1 for a quotation milestone");
        }
        record Q(UUID id, UUID serviceId, String payerRef, String status, LocalDate validUntil) {}
        Q q = jdbc.sql("""
                SELECT q.id, q.service_id, p.payer_reference, q.status, q.valid_until
                  FROM quotation q JOIN payer p ON p.id = q.payer_id
                 WHERE q.source_system_id = :src AND q.quote_reference = :ref
                   FOR UPDATE OF q
                """)
            .param("src", actor.sourceSystemId()).param("ref", line.quotationReference())
            .query((rs, n) -> new Q(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                    rs.getObject(5, LocalDate.class)))
            .optional()
            // another platform's quotation looks exactly like a missing one
            .orElseThrow(() -> ApiException.validation(field + ".quotationReference", "no quotation " + line.quotationReference()));
        if (!"Approved".equals(q.status())) {
            throw new ApiException(422, "CBR-QUO-002", "Quotation " + line.quotationReference() + " is " + q.status().toLowerCase()
                    + "; only an approved quotation can be billed");
        }
        if (q.validUntil().isBefore(LocalDate.now(BillService.BUSINESS_ZONE))) {
            throw new ApiException(422, "CBR-QUO-003", "Quotation " + line.quotationReference() + " expired on " + q.validUntil());
        }
        if (!q.serviceId().equals(tariff.serviceId())) {
            throw ApiException.validation(field + ".serviceCode", "quotation " + line.quotationReference() + " is for a different service");
        }
        if (!q.payerRef().equals(payerReference)) {
            throw ApiException.validation("payer.reference", "quotation " + line.quotationReference() + " was agreed with a different payer");
        }
        record M(UUID id, String description, BigDecimal amount) {}
        M m = jdbc.sql("SELECT id, description, amount FROM quotation_milestone WHERE quotation_id = :q AND milestone_no = :no")
                .param("q", q.id()).param("no", line.milestone())
                .query((rs, n) -> new M(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3)))
                .optional()
                .orElseThrow(() -> ApiException.validation(field + ".milestone", "quotation " + line.quotationReference()
                        + " has no milestone " + line.milestone()));
        if (!takenInThisBill.add(m.id())) {
            throw ApiException.validation(field + ".milestone", "milestone " + line.milestone() + " appears twice in this bill");
        }
        Optional<String> billedOn = jdbc.sql("""
                SELECT b.source_reference FROM bill_line l JOIN bill b ON b.id = l.bill_id
                 WHERE l.quotation_milestone_id = :m
                   AND b.preparation_state NOT IN ('Rejected', 'Superseded')
                   AND b.registration_state <> 'Cancelled'
                 LIMIT 1
                """)
            .param("m", m.id()).query(String.class).optional();
        if (billedOn.isPresent()) {
            throw ApiException.conflict("CBR-QUO-004", "Milestone " + line.milestone() + " of quotation " + line.quotationReference()
                    + " is already billed on " + billedOn.get());
        }
        return new PricedMilestone(m.id(), m.amount(), tariff.serviceName() + ": " + m.description());
    }

    public Optional<UUID> owner(UUID quotationId) {
        return jdbc.sql("SELECT source_system_id FROM quotation WHERE id = :id").param("id", quotationId).query(UUID.class).optional();
    }

    public Optional<UUID> findByReference(UUID sourceSystemId, String reference) {
        return jdbc.sql("SELECT id FROM quotation WHERE source_system_id = :src AND quote_reference = :ref")
                .param("src", sourceSystemId).param("ref", reference).query(UUID.class).optional();
    }

    public List<QuotationView> list(String status) {
        return jdbc.sql("""
                SELECT id FROM quotation WHERE (:status = '' OR status = :status) ORDER BY created_at DESC LIMIT 200
                """)
            .param("status", status == null ? "" : status)
            .query(UUID.class).list().stream().map(this::view).toList();
    }

    public QuotationView view(UUID id) {
        List<MilestoneView> milestones = jdbc.sql("""
                SELECT m.milestone_no, m.description, m.amount, b.id, 
                       CASE WHEN b.id IS NULL THEN NULL
                            WHEN b.collection_state IN ('Paid','Overpaid') THEN 'Paid'
                            WHEN b.collection_state = 'PartiallyPaid' THEN 'PartiallyPaid'
                            ELSE 'Billed' END
                  FROM quotation_milestone m
                  LEFT JOIN LATERAL (
                    SELECT b.id, b.collection_state FROM bill_line l JOIN bill b ON b.id = l.bill_id
                     WHERE l.quotation_milestone_id = m.id
                       AND b.preparation_state NOT IN ('Rejected', 'Superseded') AND b.registration_state <> 'Cancelled'
                     LIMIT 1) b ON true
                 WHERE m.quotation_id = :id
                 ORDER BY m.milestone_no
                """)
            .param("id", id)
            .query((rs, n) -> new MilestoneView(rs.getInt(1), rs.getString(2), rs.getBigDecimal(3).toPlainString(),
                    rs.getString(4), rs.getString(5)))
            .list();
        return jdbc.sql("""
                SELECT q.id, s.code, q.quote_reference, v.code, v.name, p.payer_reference, p.display_name, q.description, q.total,
                       q.currency, q.valid_until, q.status, u.display_name, q.created_at, q.decided_at,
                       coalesce((SELECT sum(m.amount) FROM quotation_milestone m
                                  WHERE m.quotation_id = q.id AND EXISTS (
                                    SELECT 1 FROM bill_line l JOIN bill b ON b.id = l.bill_id
                                     WHERE l.quotation_milestone_id = m.id
                                       AND b.preparation_state NOT IN ('Rejected', 'Superseded') AND b.registration_state <> 'Cancelled')), 0)
                  FROM quotation q
                  JOIN source_system s ON s.id = q.source_system_id
                  JOIN service v ON v.id = q.service_id
                  JOIN payer p ON p.id = q.payer_id
                  JOIN app_user u ON u.id = q.requested_by
                 WHERE q.id = :id
                """)
            .param("id", id)
            .query((rs, n) -> new QuotationView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getString(6), rs.getString(7), rs.getString(8), rs.getBigDecimal(9).toPlainString(), rs.getString(10),
                    String.valueOf(rs.getObject(11, LocalDate.class)), rs.getString(12), rs.getString(13),
                    String.valueOf(rs.getObject(14, java.time.OffsetDateTime.class)),
                    rs.getObject(15) == null ? null : String.valueOf(rs.getObject(15, java.time.OffsetDateTime.class)),
                    rs.getBigDecimal(16).toPlainString(), milestones))
            .optional()
            .orElseThrow(() -> ApiException.notFound("Quotation not found"));
    }

    static Set<UUID> newTakenSet() {
        return new HashSet<>();
    }
}
