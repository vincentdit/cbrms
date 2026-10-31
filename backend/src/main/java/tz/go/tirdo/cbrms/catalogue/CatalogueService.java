package tz.go.tirdo.cbrms.catalogue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.cbrms.audit.AuditService;
import tz.go.tirdo.cbrms.billing.BillService;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.identity.Actor;

/**
 * Catalogue maintenance (FR01, BR01). A Finance officer proposes; a different bill approver approves; CBRMS applies.
 * Prices never change retroactively: a new version starts today or later, and the version it replaces is closed
 * the day before. Bills already issued keep their own price snapshot (FR02).
 */
@Service
public class CatalogueService {

    public enum Kind { NEW_SERVICE, NEW_TARIFF, NEW_REVENUE_MAPPING, SERVICE_UPDATE }

    /** One proposal; which fields are required depends on the kind. */
    public record Proposal(Kind kind, String serviceCode, String reason, String approvalRef,
                           String name, String nameSw, String directorateCode, String unit, Integer quantityScale,
                           Boolean requiresApproval, String pricingBasis, String serviceStatus, List<String> platforms,
                           BigDecimal unitPrice, String paymentMode, String roundingRule, Integer maxValidityDays,
                           String gfsCode, String accountingCode, LocalDate effectiveFrom, LocalDate effectiveTo) {}

    public record ChangeView(String changeId, String kind, String serviceCode, String serviceName, String status, String reason,
                             String approvalRef, String requestedBy, String createdAt, String appliedAt,
                             String currentPrice, String proposedPrice, String paymentMode, String effectiveFrom, String effectiveTo,
                             String gfsCode, String accountingCode, String summary) {}

    public record TariffRow(int version, String unitPrice, String paymentMode, String roundingRule, int maxValidityDays,
                            String from, String to, String status, String approvalRef, boolean inEffect) {}

    public record MappingRow(String gfsCode, String accountingCode, String from, String to, String status, String approvalRef,
                             boolean inEffect) {}

    public record ServiceRow(String code, String name, String nameSw, String directorate, String unit, int quantityScale,
                             boolean requiresApproval, String pricingBasis, String status, List<String> platforms,
                             String currentPrice, Integer currentVersion, String paymentMode, String gfsCode,
                             String nextPrice, String nextFrom, String pendingChangeId) {}

    public record ServiceDetail(ServiceRow service, List<TariffRow> tariffs, List<MappingRow> mappings, List<ChangeView> changes) {}

    public record Directorate(String code, String name) {}

    /** What a platform needs to bill: its permitted services, the version in effect today and any scheduled change. */
    public record PlatformService(String serviceCode, String name, String nameSw, String unit, int quantityScale,
                                  String pricingBasis, boolean requiresApproval, Integer currentVersion, String currentPrice,
                                  String paymentMode, Integer nextVersion, String nextPrice, String nextFrom) {}

    private static final List<String> MODES = List.of("EXACT", "PARTIAL", "FULL");
    private static final List<String> ROUNDING = List.of("WHOLE_SHILLING_HALF_UP", "HALF_UP_2");
    private static final BigDecimal MAX_PRICE = new BigDecimal("1000000000000");

    private final JdbcClient jdbc;
    private final AuditService audit;

    public CatalogueService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- proposals

    @Transactional
    public ChangeView propose(Actor maker, Proposal p) {
        requireFinanceMaker(maker);
        LocalDate today = LocalDate.now(BillService.BUSINESS_ZONE);
        List<ApiException.FieldProblem> problems = new ArrayList<>();
        String code = p.serviceCode() == null ? "" : p.serviceCode().trim().toUpperCase();
        if (p.kind() == null) {
            throw ApiException.validation("kind", "use NEW_SERVICE, NEW_TARIFF, NEW_REVENUE_MAPPING or SERVICE_UPDATE");
        }
        if (!code.matches("[A-Z0-9][A-Z0-9-]{2,49}")) {
            problems.add(problem("serviceCode", "3 to 50 capital letters, digits or hyphens"));
        }
        if (blank(p.reason()) || p.reason().length() > 500) {
            problems.add(problem("reason", "say why, in up to 500 characters"));
        }
        boolean exists = serviceId(code).isPresent();
        String basis = p.kind() == Kind.NEW_SERVICE ? (p.pricingBasis() == null ? "TARIFF" : p.pricingBasis()) : basisOf(code);
        UUID directorateId = null;

        switch (p.kind()) {
            case NEW_SERVICE -> {
                if (exists) {
                    problems.add(problem("serviceCode", code + " already exists"));
                }
                if (blank(p.name()) || p.name().length() > 200) {
                    problems.add(problem("name", "1 to 200 characters"));
                }
                if (p.nameSw() != null && p.nameSw().length() > 200) {
                    problems.add(problem("nameSw", "at most 200 characters"));
                }
                if (blank(p.unit()) || p.unit().length() > 30) {
                    problems.add(problem("unit", "for example sample, day, participant"));
                }
                if (p.quantityScale() != null && (p.quantityScale() < 0 || p.quantityScale() > 4)) {
                    problems.add(problem("quantityScale", "0 to 4 decimal places"));
                }
                if (!List.of("TARIFF", "QUOTED").contains(basis)) {
                    problems.add(problem("pricingBasis", "TARIFF or QUOTED"));
                }
                directorateId = jdbc.sql("SELECT id FROM directorate WHERE code = :c AND status = 'ACTIVE'")
                        .param("c", p.directorateCode() == null ? "" : p.directorateCode()).query(UUID.class).optional().orElse(null);
                if (directorateId == null) {
                    problems.add(problem("directorateCode", "unknown directorate"));
                }
                checkTariff(p, basis, today, problems);
                checkMapping(p, problems);
                checkPlatforms(p.platforms(), problems);
            }
            case NEW_TARIFF -> {
                requireExists(exists, code, problems);
                checkTariff(p, basis, today, problems);
            }
            case NEW_REVENUE_MAPPING -> {
                requireExists(exists, code, problems);
                checkMapping(p, problems);
                checkStart(p, today, problems);
            }
            case SERVICE_UPDATE -> {
                requireExists(exists, code, problems);
                if (p.name() != null && (blank(p.name()) || p.name().length() > 200)) {
                    problems.add(problem("name", "1 to 200 characters"));
                }
                if (p.serviceStatus() != null && !List.of("ACTIVE", "INACTIVE").contains(p.serviceStatus())) {
                    problems.add(problem("serviceStatus", "ACTIVE or INACTIVE"));
                }
                if (p.platforms() != null) {
                    checkPlatforms(p.platforms(), problems);
                }
                if (p.name() == null && p.nameSw() == null && p.requiresApproval() == null && p.serviceStatus() == null
                        && p.platforms() == null) {
                    problems.add(problem("kind", "nothing to change"));
                }
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation("Validation failed", problems);
        }

        long open = jdbc.sql("SELECT count(*) FROM catalogue_change WHERE service_code = :c AND status = 'Requested'")
                .param("c", code).query(Long.class).single();
        if (open > 0) {
            throw ApiException.conflict("CBR-CAT-001", "There is already a change waiting for approval on " + code
                    + ". Approve or reject it first.");
        }
        boolean quoted = "QUOTED".equals(basis);
        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO catalogue_change (kind, service_code, reason, approval_ref, name, name_sw, directorate_id, unit,
                           quantity_scale, requires_approval, pricing_basis, service_status, platform_codes, unit_price, payment_mode,
                           rounding_rule, max_validity_days, gfs_code, accounting_code, effective_from, effective_to, requested_by)
                    VALUES (:kind, :code, :reason, :approvalRef, :name, :nameSw, :directorate, :unit, :scale, :requiresApproval,
                            :basis, :status, CAST(:platforms AS text[]), :price, :mode, :rounding, :validity, :gfs, :acc, :from, :to, :by)
                    RETURNING id
                    """)
                .param("kind", p.kind().name()).param("code", code).param("reason", p.reason().trim())
                .param("approvalRef", trim(p.approvalRef())).param("name", trim(p.name())).param("nameSw", trim(p.nameSw()))
                .param("directorate", directorateId).param("unit", trim(p.unit()))
                .param("scale", p.kind() == Kind.NEW_SERVICE ? (p.quantityScale() == null ? 0 : p.quantityScale()) : null)
                .param("requiresApproval", p.requiresApproval())
                .param("basis", p.kind() == Kind.NEW_SERVICE ? basis : null)
                .param("status", p.serviceStatus())
                .param("platforms", p.platforms() == null ? null : "{" + String.join(",", p.platforms()) + "}")
                .param("price", hasTariff(p.kind()) ? (quoted ? BigDecimal.ZERO.setScale(2) : p.unitPrice()) : null)
                .param("mode", hasTariff(p.kind()) ? p.paymentMode() : null)
                .param("rounding", hasTariff(p.kind()) ? p.roundingRule() : null)
                .param("validity", hasTariff(p.kind()) ? (p.maxValidityDays() == null ? 180 : p.maxValidityDays()) : null)
                .param("gfs", hasMapping(p.kind()) ? trim(p.gfsCode()) : null)
                .param("acc", hasMapping(p.kind()) ? trim(p.accountingCode()) : null)
                .param("from", p.kind() == Kind.SERVICE_UPDATE ? null : p.effectiveFrom())
                .param("to", p.kind() == Kind.SERVICE_UPDATE ? null : p.effectiveTo())
                .param("by", maker.userId())
                .query(UUID.class).single();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("CBR-CAT-001", "There is already a change waiting for approval on " + code
                    + ". Approve or reject it first.");
        }
        UUID approval = jdbc.sql("INSERT INTO approval (subject_type, subject_id, maker_id) VALUES ('CATALOGUE', :id, :maker) RETURNING id")
                .param("id", id).param("maker", maker.userId()).query(UUID.class).single();
        jdbc.sql("UPDATE catalogue_change SET approval_id = :a WHERE id = :id").param("a", approval).param("id", id).update();
        audit.record(maker.subject(), "CATALOGUE_CHANGE_PROPOSED", "catalogue:" + code, p.reason(), Map.of("kind", p.kind().name()));
        return change(id);
    }

    /** Approve (and apply in the same transaction) or reject a proposal. The maker cannot decide on their own proposal. */
    @Transactional
    public ChangeView decide(Actor approver, UUID changeId, String decision, String reason) {
        if (!approver.finance()) {
            throw ApiException.forbidden("CBR-AUT-006", "Only Finance can approve catalogue changes");
        }
        record Open(String status, UUID approvalId, UUID makerId, String code) {}
        Open c = jdbc.sql("""
                SELECT c.status, c.approval_id, c.requested_by, c.service_code FROM catalogue_change c WHERE c.id = :id FOR UPDATE
                """)
            .param("id", changeId)
            .query((rs, n) -> new Open(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4)))
            .optional()
            .orElseThrow(() -> ApiException.notFound("Catalogue change not found"));
        if (!"Requested".equals(c.status())) {
            throw ApiException.conflict("CBR-STA-001", "This change is already " + c.status().toLowerCase());
        }
        if (approver.userId().equals(c.makerId())) {
            throw ApiException.forbidden("CBR-AUT-003", "The officer who proposed a catalogue change cannot approve it");
        }
        boolean approve = "APPROVED".equals(decision);
        if (!approve && blank(reason)) {
            throw ApiException.validation("reason", "a reason is required to reject");
        }
        jdbc.sql("UPDATE approval SET approver_id = :by, decision = :d, reason = :r, decided_at = now() WHERE id = :id")
            .param("by", approver.userId()).param("d", approve ? "APPROVED" : "REJECTED").param("r", reason).param("id", c.approvalId())
            .update();
        if (approve) {
            apply(changeId);
        }
        jdbc.sql("UPDATE catalogue_change SET status = :s, applied_at = CASE WHEN :s = 'Applied' THEN now() END WHERE id = :id")
            .param("s", approve ? "Applied" : "Rejected").param("id", changeId).update();
        audit.record(approver.subject(), approve ? "CATALOGUE_CHANGE_APPLIED" : "CATALOGUE_CHANGE_REJECTED", "catalogue:" + c.code(),
                reason, Map.of("changeId", changeId.toString()));
        return change(changeId);
    }

    // ---------------------------------------------------------------- applying

    private record Change(String kind, String code, String approvalRef, String name, String nameSw, UUID directorateId, String unit,
                          Integer scale, Boolean requiresApproval, String basis, String serviceStatus, String[] platforms,
                          BigDecimal price, String mode, String rounding, Integer validity, String gfs, String acc,
                          LocalDate from, LocalDate to) {}

    private void apply(UUID changeId) {
        Change c = jdbc.sql("""
                SELECT kind, service_code, approval_ref, name, name_sw, directorate_id, unit, quantity_scale, requires_approval,
                       pricing_basis, service_status, platform_codes, unit_price, payment_mode, rounding_rule, max_validity_days,
                       gfs_code, accounting_code, effective_from, effective_to
                  FROM catalogue_change WHERE id = :id
                """)
            .param("id", changeId)
            .query((rs, n) -> {
                java.sql.Array arr = rs.getArray(12);
                return new Change(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, UUID.class), rs.getString(7), (Integer) rs.getObject(8), (Boolean) rs.getObject(9),
                        rs.getString(10), rs.getString(11), arr == null ? null : (String[]) arr.getArray(),
                        rs.getBigDecimal(13), rs.getString(14), rs.getString(15), (Integer) rs.getObject(16), rs.getString(17),
                        rs.getString(18), rs.getObject(19, LocalDate.class), rs.getObject(20, LocalDate.class));
            })
            .single();
        LocalDate today = LocalDate.now(BillService.BUSINESS_ZONE);
        if (c.from() != null && c.from().isBefore(today)) {
            // approved too late: the start date has passed, and prices are never backdated
            throw new ApiException(422, "CBR-CAT-002", "This change was due to start on " + c.from()
                    + ", which has passed. Reject it and propose it again with a new start date.");
        }
        switch (Kind.valueOf(c.kind())) {
            case NEW_SERVICE -> {
                UUID serviceId = jdbc.sql("""
                        INSERT INTO service (code, name, name_sw, directorate_id, unit, quantity_scale, requires_approval, pricing_basis)
                        VALUES (:code, :name, :nameSw, :dir, :unit, :scale, :ra, :basis) RETURNING id
                        """)
                    .param("code", c.code()).param("name", c.name()).param("nameSw", c.nameSw()).param("dir", c.directorateId())
                    .param("unit", c.unit()).param("scale", c.scale()).param("ra", Boolean.TRUE.equals(c.requiresApproval()))
                    .param("basis", c.basis())
                    .query(UUID.class).single();
                insertTariff(serviceId, 1, c, changeId);
                insertMapping(serviceId, c, changeId);
                grant(serviceId, c.platforms());
            }
            case NEW_TARIFF -> {
                UUID serviceId = serviceId(c.code()).orElseThrow();
                closePrevious("tariff_version", serviceId, c.from());
                int next = jdbc.sql("SELECT coalesce(max(version_no), 0) + 1 FROM tariff_version WHERE service_id = :s")
                        .param("s", serviceId).query(Integer.class).single();
                insertTariff(serviceId, next, c, changeId);
            }
            case NEW_REVENUE_MAPPING -> {
                UUID serviceId = serviceId(c.code()).orElseThrow();
                closePrevious("revenue_mapping", serviceId, c.from());
                insertMapping(serviceId, c, changeId);
            }
            case SERVICE_UPDATE -> {
                UUID serviceId = serviceId(c.code()).orElseThrow();
                jdbc.sql("""
                        UPDATE service SET name = coalesce(:name, name), name_sw = coalesce(:nameSw, name_sw),
                               requires_approval = coalesce(:ra, requires_approval), status = coalesce(:status, status)
                         WHERE id = :id
                        """)
                    .param("name", c.name()).param("nameSw", c.nameSw()).param("ra", c.requiresApproval())
                    .param("status", c.serviceStatus()).param("id", serviceId)
                    .update();
                if (c.platforms() != null) {
                    jdbc.sql("DELETE FROM source_system_service WHERE service_id = :s").param("s", serviceId).update();
                    grant(serviceId, c.platforms());
                }
            }
        }
    }

    /**
     * Ends the active row that covers the new start date the day before it, so the database's no-overlap rule holds.
     * A later version that already starts on or after the new date is a conflict, never silently replaced.
     */
    private void closePrevious(String table, UUID serviceId, LocalDate from) {
        long later = jdbc.sql("SELECT count(*) FROM " + table + " WHERE service_id = :s AND status = 'ACTIVE' AND lower(effective_period) >= :from")
                .param("s", serviceId).param("from", from).query(Long.class).single();
        if (later > 0) {
            throw ApiException.conflict("CBR-CAT-003", "An approved version already starts on or after " + from
                    + ". Propose a start date after it.");
        }
        jdbc.sql("UPDATE " + table + " SET effective_period = daterange(lower(effective_period), :from)"
                        + " WHERE service_id = :s AND status = 'ACTIVE' AND effective_period @> CAST(:from AS date)")
            .param("s", serviceId).param("from", from).update();
    }

    private void insertTariff(UUID serviceId, int version, Change c, UUID changeId) {
        jdbc.sql("""
                INSERT INTO tariff_version (service_id, version_no, currency, unit_price, payment_mode, rounding_rule, max_validity_days,
                                            effective_period, approval_ref, status, catalogue_change_id)
                VALUES (:s, :v, 'TZS', :price, :mode, :rounding, :validity, daterange(:from, :to), :ref, 'ACTIVE', :change)
                """)
            .param("s", serviceId).param("v", version).param("price", c.price()).param("mode", c.mode()).param("rounding", c.rounding())
            .param("validity", c.validity()).param("from", c.from()).param("to", c.to()).param("ref", c.approvalRef())
            .param("change", changeId)
            .update();
    }

    private void insertMapping(UUID serviceId, Change c, UUID changeId) {
        jdbc.sql("""
                INSERT INTO revenue_mapping (service_id, gfs_code, accounting_code, effective_period, approval_ref, status, catalogue_change_id)
                VALUES (:s, :gfs, :acc, daterange(:from, :to), :ref, 'ACTIVE', :change)
                """)
            .param("s", serviceId).param("gfs", c.gfs()).param("acc", c.acc()).param("from", c.from()).param("to", c.to())
            .param("ref", c.approvalRef()).param("change", changeId)
            .update();
    }

    private void grant(UUID serviceId, String[] platforms) {
        if (platforms == null) {
            return;
        }
        for (String code : platforms) {
            jdbc.sql("""
                    INSERT INTO source_system_service (source_system_id, service_id)
                    SELECT id, :s FROM source_system WHERE code = :code ON CONFLICT DO NOTHING
                    """)
                .param("s", serviceId).param("code", code).update();
        }
    }

    // ---------------------------------------------------------------- reading

    public List<ServiceRow> services() {
        return jdbc.sql("SELECT code FROM service ORDER BY code").query(String.class).list().stream().map(this::serviceRow).toList();
    }

    public ServiceDetail detail(String code) {
        UUID id = serviceId(code).orElseThrow(() -> ApiException.notFound("Service not found"));
        List<TariffRow> tariffs = jdbc.sql("""
                SELECT version_no, unit_price, payment_mode, rounding_rule, max_validity_days, lower(effective_period),
                       upper(effective_period), status, approval_ref,
                       status = 'ACTIVE' AND effective_period @> (now() AT TIME ZONE 'Africa/Dar_es_Salaam')::date
                  FROM tariff_version WHERE service_id = :s ORDER BY version_no DESC
                """)
            .param("s", id)
            .query((rs, n) -> new TariffRow(rs.getInt(1), rs.getBigDecimal(2).toPlainString(), rs.getString(3), rs.getString(4),
                    rs.getInt(5), str(rs.getObject(6, LocalDate.class)), str(rs.getObject(7, LocalDate.class)), rs.getString(8),
                    rs.getString(9), rs.getBoolean(10)))
            .list();
        List<MappingRow> mappings = jdbc.sql("""
                SELECT gfs_code, accounting_code, lower(effective_period), upper(effective_period), status, approval_ref,
                       status = 'ACTIVE' AND effective_period @> (now() AT TIME ZONE 'Africa/Dar_es_Salaam')::date
                  FROM revenue_mapping WHERE service_id = :s ORDER BY lower(effective_period) DESC
                """)
            .param("s", id)
            .query((rs, n) -> new MappingRow(rs.getString(1), rs.getString(2), str(rs.getObject(3, LocalDate.class)),
                    str(rs.getObject(4, LocalDate.class)), rs.getString(5), rs.getString(6), rs.getBoolean(7)))
            .list();
        List<ChangeView> changes = jdbc.sql("SELECT id FROM catalogue_change WHERE service_code = :c ORDER BY created_at DESC LIMIT 50")
                .param("c", code).query(UUID.class).list().stream().map(this::change).toList();
        return new ServiceDetail(serviceRow(code), tariffs, mappings, changes);
    }

    public List<ChangeView> changes(String status) {
        return jdbc.sql("SELECT id FROM catalogue_change WHERE (:s = '' OR status = :s) ORDER BY created_at DESC LIMIT 200")
                .param("s", status == null ? "" : status).query(UUID.class).list().stream().map(this::change).toList();
    }

    public List<PlatformService> forPlatform(UUID sourceSystemId) {
        return jdbc.sql("""
                SELECT s.code, s.name, s.name_sw, s.unit, s.quantity_scale, s.pricing_basis, s.requires_approval,
                       cur.version_no, cur.unit_price, cur.payment_mode, nxt.version_no, nxt.unit_price, lower(nxt.effective_period)
                  FROM source_system_service m
                  JOIN service s ON s.id = m.service_id AND s.status = 'ACTIVE'
                  LEFT JOIN LATERAL (SELECT t.* FROM tariff_version t WHERE t.service_id = s.id AND t.status = 'ACTIVE'
                                       AND t.effective_period @> (now() AT TIME ZONE 'Africa/Dar_es_Salaam')::date LIMIT 1) cur ON true
                  LEFT JOIN LATERAL (SELECT t.* FROM tariff_version t WHERE t.service_id = s.id AND t.status = 'ACTIVE'
                                       AND lower(t.effective_period) > (now() AT TIME ZONE 'Africa/Dar_es_Salaam')::date
                                     ORDER BY lower(t.effective_period) LIMIT 1) nxt ON true
                 WHERE m.source_system_id = :src
                 ORDER BY s.code
                """)
            .param("src", sourceSystemId)
            .query((rs, n) -> new PlatformService(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                    rs.getString(6), rs.getBoolean(7), (Integer) rs.getObject(8), plain(rs.getBigDecimal(9)), rs.getString(10),
                    (Integer) rs.getObject(11), plain(rs.getBigDecimal(12)), str(rs.getObject(13, LocalDate.class))))
            .list();
    }

    public List<Directorate> directorates() {
        return jdbc.sql("SELECT code, name FROM directorate WHERE status = 'ACTIVE' ORDER BY code")
                .query((rs, n) -> new Directorate(rs.getString(1), rs.getString(2))).list();
    }

    private ServiceRow serviceRow(String code) {
        return jdbc.sql("""
                SELECT s.code, s.name, s.name_sw, d.name, s.unit, s.quantity_scale, s.requires_approval, s.pricing_basis, s.status,
                       coalesce((SELECT string_agg(x.code, ',' ORDER BY x.code) FROM source_system_service m
                                   JOIN source_system x ON x.id = m.source_system_id WHERE m.service_id = s.id), ''),
                       cur.unit_price, cur.version_no, cur.payment_mode, map.gfs_code, nxt.unit_price, lower(nxt.effective_period),
                       (SELECT c.id FROM catalogue_change c WHERE c.service_code = s.code AND c.status = 'Requested')
                  FROM service s
                  JOIN directorate d ON d.id = s.directorate_id
                  LEFT JOIN LATERAL (SELECT t.* FROM tariff_version t WHERE t.service_id = s.id AND t.status = 'ACTIVE'
                                       AND t.effective_period @> (now() AT TIME ZONE 'Africa/Dar_es_Salaam')::date LIMIT 1) cur ON true
                  LEFT JOIN LATERAL (SELECT t.* FROM tariff_version t WHERE t.service_id = s.id AND t.status = 'ACTIVE'
                                       AND lower(t.effective_period) > (now() AT TIME ZONE 'Africa/Dar_es_Salaam')::date
                                     ORDER BY lower(t.effective_period) LIMIT 1) nxt ON true
                  LEFT JOIN LATERAL (SELECT r.* FROM revenue_mapping r WHERE r.service_id = s.id AND r.status = 'ACTIVE'
                                       AND r.effective_period @> (now() AT TIME ZONE 'Africa/Dar_es_Salaam')::date LIMIT 1) map ON true
                 WHERE s.code = :code
                """)
            .param("code", code)
            .query((rs, n) -> new ServiceRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getInt(6), rs.getBoolean(7), rs.getString(8), rs.getString(9),
                    rs.getString(10).isEmpty() ? List.of() : Arrays.asList(rs.getString(10).split(",")),
                    plain(rs.getBigDecimal(11)), (Integer) rs.getObject(12), rs.getString(13), rs.getString(14),
                    plain(rs.getBigDecimal(15)), str(rs.getObject(16, LocalDate.class)), rs.getString(17)))
            .single();
    }

    public ChangeView change(UUID id) {
        return jdbc.sql("""
                SELECT c.id, c.kind, c.service_code, coalesce(s.name, c.name), c.status, c.reason, c.approval_ref, u.display_name,
                       c.created_at, c.applied_at,
                       (SELECT t.unit_price FROM tariff_version t WHERE t.service_id = s.id AND t.status = 'ACTIVE'
                           AND t.effective_period @> (now() AT TIME ZONE 'Africa/Dar_es_Salaam')::date LIMIT 1),
                       c.unit_price, c.payment_mode, c.effective_from, c.effective_to, c.gfs_code, c.accounting_code,
                       c.name, c.service_status, c.requires_approval, c.platform_codes
                  FROM catalogue_change c
                  JOIN app_user u ON u.id = c.requested_by
                  LEFT JOIN service s ON s.code = c.service_code
                 WHERE c.id = :id
                """)
            .param("id", id)
            .query((rs, n) -> {
                String kind = rs.getString(2);
                java.sql.Array arr = rs.getArray(21);
                String summary = switch (kind) {
                    case "NEW_SERVICE" -> "New service: " + rs.getString(18);
                    case "NEW_TARIFF" -> "New price TZS " + plain(rs.getBigDecimal(12)) + " (" + rs.getString(13) + ") from "
                            + rs.getObject(14, LocalDate.class);
                    case "NEW_REVENUE_MAPPING" -> "Revenue codes GFS " + rs.getString(16) + " / " + rs.getString(17) + " from "
                            + rs.getObject(14, LocalDate.class);
                    default -> {
                        List<String> parts = new ArrayList<>();
                        if (rs.getString(18) != null) {
                            parts.add("rename to " + rs.getString(18));
                        }
                        if (rs.getString(19) != null) {
                            parts.add("ACTIVE".equals(rs.getString(19)) ? "reactivate" : "deactivate");
                        }
                        if (rs.getObject(20) != null) {
                            parts.add(rs.getBoolean(20) ? "require bill approval" : "no bill approval");
                        }
                        if (arr != null) {
                            parts.add("platforms " + String.join(", ", (String[]) arr.getArray()).toUpperCase());
                        }
                        yield "Settings: " + String.join("; ", parts);
                    }
                };
                return new ChangeView(rs.getString(1), kind, rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), str(rs.getObject(9, java.time.OffsetDateTime.class)),
                        str(rs.getObject(10, java.time.OffsetDateTime.class)), plain(rs.getBigDecimal(11)), plain(rs.getBigDecimal(12)),
                        rs.getString(13), str(rs.getObject(14, LocalDate.class)), str(rs.getObject(15, LocalDate.class)),
                        rs.getString(16), rs.getString(17), summary);
            })
            .optional()
            .orElseThrow(() -> ApiException.notFound("Catalogue change not found"));
    }

    // ---------------------------------------------------------------- checks and helpers

    private void checkTariff(Proposal p, String basis, LocalDate today, List<ApiException.FieldProblem> problems) {
        boolean quoted = "QUOTED".equals(basis);
        if (!quoted && (p.unitPrice() == null || p.unitPrice().signum() <= 0 || p.unitPrice().scale() > 2
                || p.unitPrice().compareTo(MAX_PRICE) >= 0)) {
            problems.add(problem("unitPrice", "a positive TZS amount with at most 2 decimals"));
        }
        if (p.paymentMode() == null || !MODES.contains(p.paymentMode())) {
            problems.add(problem("paymentMode", "EXACT, PARTIAL or FULL"));
        }
        if (p.roundingRule() == null || !ROUNDING.contains(p.roundingRule())) {
            problems.add(problem("roundingRule", "WHOLE_SHILLING_HALF_UP or HALF_UP_2"));
        }
        if (p.maxValidityDays() != null && (p.maxValidityDays() < 1 || p.maxValidityDays() > 365)) {
            problems.add(problem("maxValidityDays", "1 to 365 days"));
        }
        checkStart(p, today, problems);
    }

    private void checkMapping(Proposal p, List<ApiException.FieldProblem> problems) {
        if (blank(p.gfsCode()) || p.gfsCode().length() > 30) {
            problems.add(problem("gfsCode", "the GFS revenue code"));
        }
        if (blank(p.accountingCode()) || p.accountingCode().length() > 30) {
            problems.add(problem("accountingCode", "the accounting code"));
        }
    }

    private static void checkStart(Proposal p, LocalDate today, List<ApiException.FieldProblem> problems) {
        if (p.effectiveFrom() == null || p.effectiveFrom().isBefore(today)) {
            problems.add(problem("effectiveFrom", "today or later; prices are never backdated"));
        } else if (p.effectiveFrom().isAfter(today.plusYears(2))) {
            problems.add(problem("effectiveFrom", "at most two years ahead"));
        }
        if (p.effectiveTo() != null && p.effectiveFrom() != null && !p.effectiveTo().isAfter(p.effectiveFrom())) {
            problems.add(problem("effectiveTo", "after the start date"));
        }
        if (blank(p.approvalRef()) || p.approvalRef().length() > 100) {
            problems.add(problem("approvalRef", "the Finance authority for this change, for example a DG approval number"));
        }
    }

    private void checkPlatforms(List<String> platforms, List<ApiException.FieldProblem> problems) {
        if (platforms == null) {
            return;
        }
        for (String code : platforms) {
            boolean known = jdbc.sql("SELECT count(*) FROM source_system WHERE code = :c").param("c", code == null ? "" : code)
                    .query(Long.class).single() > 0;
            if (!known) {
                problems.add(problem("platforms", "unknown platform " + code));
            }
        }
    }

    private static void requireExists(boolean exists, String code, List<ApiException.FieldProblem> problems) {
        if (!exists) {
            problems.add(problem("serviceCode", "no service " + code));
        }
    }

    private static void requireFinanceMaker(Actor a) {
        if (!a.finance()) {
            throw ApiException.forbidden("CBR-AUT-006", "Catalogue changes are proposed by Finance");
        }
    }

    private Optional<UUID> serviceId(String code) {
        return jdbc.sql("SELECT id FROM service WHERE code = :c").param("c", code).query(UUID.class).optional();
    }

    private String basisOf(String code) {
        return jdbc.sql("SELECT pricing_basis FROM service WHERE code = :c").param("c", code).query(String.class).optional().orElse("TARIFF");
    }

    private static boolean hasTariff(Kind k) {
        return k == Kind.NEW_SERVICE || k == Kind.NEW_TARIFF;
    }

    private static boolean hasMapping(Kind k) {
        return k == Kind.NEW_SERVICE || k == Kind.NEW_REVENUE_MAPPING;
    }

    private static ApiException.FieldProblem problem(String field, String message) {
        return new ApiException.FieldProblem(field, message);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String plain(BigDecimal d) {
        return d == null ? null : d.toPlainString();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
