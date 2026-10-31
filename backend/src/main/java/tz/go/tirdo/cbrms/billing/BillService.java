package tz.go.tirdo.cbrms.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.cbrms.audit.AuditService;
import tz.go.tirdo.cbrms.catalogue.PricedTariff;
import tz.go.tirdo.cbrms.catalogue.TariffRepository;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.common.Digests;
import tz.go.tirdo.cbrms.common.Json;
import tz.go.tirdo.cbrms.config.CbrmsProperties;
import tz.go.tirdo.cbrms.domain.BillCalculator;
import tz.go.tirdo.cbrms.domain.BillStates;
import tz.go.tirdo.cbrms.domain.PaymentMode;
import tz.go.tirdo.cbrms.events.EventSubject;
import tz.go.tirdo.cbrms.events.OutboxService;
import tz.go.tirdo.cbrms.identity.Actor;

/** Bill creation (FR01 to FR07, BR02, BR03). */
@Service
public class BillService {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    static final String OP_CREATE = "createBill";

    public record CreateOutcome(UUID billId, boolean created) {}

    /** unitPrice and description come from the tariff, or from the quotation milestone for QUOTED services. */
    private record PricedLine(PricedTariff tariff, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal, String description,
                              UUID milestoneId) {}

    private final JdbcClient jdbc;
    private final TariffRepository tariffs;
    private final BillQueries bills;
    private final IdempotencyRepository idempotency;
    private final OutboxService outbox;
    private final AuditService audit;
    private final CbrmsProperties props;
    private final PayerRepository payers;
    private final QuotationService quotations;

    public BillService(JdbcClient jdbc, TariffRepository tariffs, BillQueries bills, IdempotencyRepository idempotency,
                       OutboxService outbox, AuditService audit, CbrmsProperties props, PayerRepository payers,
                       QuotationService quotations) {
        this.payers = payers;
        this.quotations = quotations;
        this.jdbc = jdbc;
        this.tariffs = tariffs;
        this.bills = bills;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.audit = audit;
        this.props = props;
    }

    @Transactional
    public CreateOutcome create(Actor actor, String idempotencyKey, CreateBillRequest req) {
        if (!actor.isPlatform()) {
            throw ApiException.forbidden("CBR-AUT-005", "Bills are created by registered source platforms");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw ApiException.validation("Idempotency-Key", "header is required (1 to 100 characters)");
        }
        String digest = Digests.sha256(canonical(req));

        // 1. Identical retry returns the original result; same key with different content is a conflict.
        Optional<IdempotencyRepository.Entry> previous = idempotency.find(actor.subject(), OP_CREATE, idempotencyKey);
        if (previous.isPresent()) {
            return replay(previous.get(), digest);
        }

        // 2. One active bill per source liability (BR03): a repeated request returns the existing bill.
        Optional<UUID> active = bills.findActive(actor.sourceSystemId(), req.sourceReference());
        if (active.isPresent()) {
            remember(actor, idempotencyKey, digest, active.get());
            return new CreateOutcome(active.get(), false);
        }

        if (req.replacesBillId() != null) {
            checkReplaceable(actor, req);
        }

        // 3. Price every line from the approved catalogue; the client never supplies amounts.
        if (req.lines().size() > props.billing().maxLines()) {
            throw ApiException.validation("lines", "at most " + props.billing().maxLines() + " lines per bill");
        }
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        List<PricedLine> priced = new ArrayList<>();
        List<ApiException.FieldProblem> problems = new ArrayList<>();
        java.util.Set<UUID> milestonesTaken = QuotationService.newTakenSet();
        for (int i = 0; i < req.lines().size(); i++) {
            CreateBillRequest.Line line = req.lines().get(i);
            String field = "lines[" + i + "]";
            PricedTariff t = tariffs.resolve(actor.sourceSystemId(), line.serviceCode(), line.tariffVersion(), today, field);
            if (t.quoted()) {
                // Price from the approved quotation milestone (locked, so it cannot be billed twice)
                QuotationService.PricedMilestone m = quotations.priceMilestone(actor, req.payer().reference(), line, t, field, milestonesTaken);
                priced.add(new PricedLine(t, BigDecimal.ONE, m.amount(), m.amount(), m.description(), m.milestoneId()));
                continue;
            }
            if (line.quotationReference() != null || line.milestone() != null) {
                problems.add(new ApiException.FieldProblem(field + ".quotationReference",
                        t.serviceCode() + " is priced from the catalogue; remove quotationReference and milestone"));
                continue;
            }
            if (!BillCalculator.quantityScaleAllowed(line.quantity(), t.quantityScale())) {
                problems.add(new ApiException.FieldProblem("lines[" + i + "].quantity",
                        "at most " + t.quantityScale() + " decimal places for " + t.serviceCode()));
                continue;
            }
            priced.add(new PricedLine(t, line.quantity(), t.unitPrice(),
                    BillCalculator.lineTotal(line.quantity(), t.unitPrice(), t.roundingRule()), t.serviceName(), null));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation("Validation failed", problems);
        }
        PaymentMode mode = priced.get(0).tariff().paymentMode();
        if (priced.stream().anyMatch(p -> p.tariff().paymentMode() != mode)) {
            throw ApiException.validation("lines", "services with different payment modes must be billed separately");
        }
        int validityDays = req.validityDays() == null ? props.billing().defaultValidityDays() : req.validityDays();
        int maxValidity = priced.stream().mapToInt(p -> p.tariff().maxValidityDays()).min().orElse(validityDays);
        if (validityDays > maxValidity) {
            throw ApiException.validation("validityDays", "at most " + maxValidity + " days for these services");
        }
        BigDecimal total = BillCalculator.billTotal(priced.stream().map(PricedLine::lineTotal).toList());
        boolean needsApproval = priced.stream().anyMatch(p -> p.tariff().requiresApproval());
        BillStates.Preparation preparation = needsApproval ? BillStates.Preparation.AwaitingApproval : BillStates.Preparation.Approved;
        LocalDate dueDate = today.plusDays(validityDays);
        OffsetDateTime validUntil = dueDate.atTime(LocalTime.of(23, 59, 59)).atZone(BUSINESS_ZONE).toOffsetDateTime();

        // 4. Payer and contacts (restricted personal data, PDPA 2022)
        UUID payerId = payers.upsert(actor.sourceSystemId(), req.payer(), req.language() == null ? "sw" : req.language());

        // 5. Insert the bill. ON CONFLICT covers a concurrent request for the same liability.
        Optional<UUID> inserted = jdbc.sql("""
                INSERT INTO bill (source_system_id, source_reference, liability_version, payer_id, currency, payment_mode, total,
                                  description, due_date, valid_until, preparation_state, created_by)
                VALUES (:src, :ref,
                        (SELECT coalesce(max(liability_version), 0) + 1 FROM bill WHERE source_system_id = :src AND source_reference = :ref),
                        :payer, :currency, :mode, :total, :description, :dueDate, :validUntil, :preparation, :createdBy)
                ON CONFLICT DO NOTHING
                RETURNING id
                """)
            .param("src", actor.sourceSystemId())
            .param("ref", req.sourceReference())
            .param("payer", payerId)
            .param("currency", req.currency())
            .param("mode", mode.name())
            .param("total", total)
            .param("description", req.description())
            .param("dueDate", dueDate)
            .param("validUntil", validUntil)
            .param("preparation", preparation.name())
            .param("createdBy", actor.userId())
            .query(UUID.class)
            .optional();
        if (inserted.isEmpty()) {
            UUID existing = bills.findActive(actor.sourceSystemId(), req.sourceReference())
                    .orElseThrow(() -> ApiException.conflict("CBR-STA-002", "Concurrent change to this liability; retry the request"));
            remember(actor, idempotencyKey, digest, existing);
            return new CreateOutcome(existing, false);
        }
        UUID billId = inserted.get();
        if (req.replacesBillId() != null) {
            linkReplacement(actor, req, billId);
        }

        for (int i = 0; i < priced.size(); i++) {
            PricedLine p = priced.get(i);
            jdbc.sql("""
                    INSERT INTO bill_line (bill_id, line_no, service_id, tariff_version_id, revenue_mapping_id, gfs_code,
                                           description, quantity, unit_price, line_total, quotation_milestone_id)
                    VALUES (:bill, :no, :service, :tariff, :mapping, :gfs, :description, :quantity, :unitPrice, :lineTotal, :milestone)
                    """)
                .param("bill", billId)
                .param("no", i + 1)
                .param("service", p.tariff().serviceId())
                .param("tariff", p.tariff().tariffVersionId())
                .param("mapping", p.tariff().revenueMappingId())
                .param("gfs", p.tariff().gfsCode())
                .param("description", p.description())
                .param("quantity", p.quantity())
                .param("unitPrice", p.unitPrice())
                .param("lineTotal", p.lineTotal())
                .param("milestone", p.milestoneId())
                .update();
        }

        if (needsApproval) {
            jdbc.sql("INSERT INTO approval (subject_type, subject_id, maker_id) VALUES ('BILL', :bill, :maker)")
                .param("bill", billId)
                .param("maker", actor.userId())
                .update();
        }

        remember(actor, idempotencyKey, digest, billId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("currency", req.currency());
        data.put("paymentMode", mode);
        data.put("preparationState", preparation);
        data.put("dueDate", dueDate);
        outbox.append(new EventSubject(billId, actor.sourceSystemId(), actor.sourceSystemCode(), req.sourceReference()), "bill.created", data);
        audit.record(actor.subject(), "BILL_CREATED", "bill:" + billId, null, Map.of("total", total, "lines", priced.size()));
        return new CreateOutcome(billId, true);
    }

    /** FR11: a replacement is allowed only for a cancelled, unpaid bill of the same liability. */
    private void checkReplaceable(Actor actor, CreateBillRequest req) {
        record Original(UUID sourceSystemId, String sourceReference, String registration, String collection, String preparation) {}
        Original o = jdbc.sql("SELECT source_system_id, source_reference, registration_state, collection_state, preparation_state FROM bill WHERE id = :id")
                .param("id", req.replacesBillId())
                .query((rs, n) -> new Original(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .optional()
                .orElseThrow(() -> ApiException.validation("replacesBillId", "bill not found"));
        if (!o.sourceSystemId().equals(actor.sourceSystemId()) || !o.sourceReference().equals(req.sourceReference())) {
            throw ApiException.validation("replacesBillId", "must be a bill of this platform with the same sourceReference");
        }
        if (!"Cancelled".equals(o.registration()) || !"Unpaid".equals(o.collection()) || "Superseded".equals(o.preparation())) {
            throw ApiException.conflict("CBR-STA-005", "Only a cancelled, unpaid bill that has not already been replaced can be replaced");
        }
    }

    private void linkReplacement(Actor actor, CreateBillRequest req, UUID newBillId) {
        jdbc.sql("UPDATE bill SET replaces_bill_id = :old WHERE id = :new").param("old", req.replacesBillId()).param("new", newBillId).update();
        jdbc.sql("UPDATE bill SET preparation_state = 'Superseded', row_version = row_version + 1 WHERE id = :old")
            .param("old", req.replacesBillId()).update();
        audit.record(actor.subject(), "BILL_REPLACED", "bill:" + req.replacesBillId(), null, Map.of("replacement", newBillId.toString()));
    }

    private CreateOutcome replay(IdempotencyRepository.Entry entry, String digest) {
        if (!entry.requestDigest().equals(digest)) {
            throw ApiException.conflict("CBR-IDM-001", "Idempotency-Key reused with different content");
        }
        return new CreateOutcome(entry.resourceId(), false);
    }

    private void remember(Actor actor, String key, String digest, UUID billId) {
        if (!idempotency.save(actor.subject(), OP_CREATE, key, digest, billId, props.billing().idempotencyRetention())) {
            // A concurrent request stored this key first; it must describe the same request.
            idempotency.find(actor.subject(), OP_CREATE, key).ifPresent(e -> replay(e, digest));
        }
    }

    /** Stable request fingerprint for idempotency; quantities are normalised so 1 and 1.0 match. */
    static String canonical(CreateBillRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceReference", r.sourceReference());
        m.put("payer", r.payer() == null ? null : List.of(nz(r.payer().reference()), nz(r.payer().name()), nz(r.payer().phone()), nz(r.payer().email())));
        m.put("currency", r.currency());
        m.put("description", r.description());
        m.put("validityDays", r.validityDays());
        m.put("replacesBillId", r.replacesBillId() == null ? null : r.replacesBillId().toString());
        m.put("language", r.language());
        m.put("lines", r.lines().stream()
                .map(l -> {
                    List<String> parts = new ArrayList<>(List.of(nz(l.serviceCode()), l.tariffVersion() == null ? "" : l.tariffVersion().toString(),
                            l.quantity() == null ? "" : l.quantity().stripTrailingZeros().toPlainString()));
                    // added only when used, so fingerprints of existing catalogue requests are unchanged
                    if (l.quotationReference() != null || l.milestone() != null) {
                        parts.add(nz(l.quotationReference()));
                        parts.add(l.milestone() == null ? "" : l.milestone().toString());
                    }
                    return parts;
                })
                .toList());
        return Json.write(m);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
