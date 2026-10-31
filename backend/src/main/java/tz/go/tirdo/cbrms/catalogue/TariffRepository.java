package tz.go.tirdo.cbrms.catalogue;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.domain.PaymentMode;
import tz.go.tirdo.cbrms.domain.RoundingRule;

@Repository
public class TariffRepository {
    private final JdbcClient jdbc;

    public TariffRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Row(UUID serviceId, String code, String name, int scale, boolean requiresApproval, String serviceStatus,
                       UUID tariffId, java.math.BigDecimal unitPrice, String mode, String rounding, int maxValidity,
                       String tariffStatus, boolean inEffect, UUID mappingId, String gfsCode, boolean permitted) {}

    /**
     * Resolves the price CBRMS will charge. The client never supplies a price or total (FR03, FR01, RU03).
     * @param field request field path used in error messages, for example "lines[0]"
     */
    public PricedTariff resolve(UUID sourceSystemId, String serviceCode, int tariffVersion, LocalDate onDate, String field) {
        Row r = jdbc.sql("""
                SELECT s.id, s.code, s.name, s.quantity_scale, s.requires_approval, s.status,
                       t.id, t.unit_price, t.payment_mode, t.rounding_rule, t.max_validity_days, t.status,
                       (t.effective_period @> CAST(:onDate AS date)),
                       m.id, m.gfs_code,
                       EXISTS (SELECT 1 FROM source_system_service p
                                WHERE p.source_system_id = :sourceSystemId AND p.service_id = s.id)
                  FROM service s
                  JOIN tariff_version t ON t.service_id = s.id AND t.version_no = :version AND t.currency = 'TZS'
                  LEFT JOIN revenue_mapping m ON m.service_id = s.id AND m.status = 'ACTIVE'
                                             AND m.effective_period @> CAST(:onDate AS date)
                 WHERE s.code = :code
                """)
            .param("onDate", onDate)
            .param("sourceSystemId", sourceSystemId)
            .param("version", tariffVersion)
            .param("code", serviceCode)
            .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBoolean(5),
                    rs.getString(6), rs.getObject(7, UUID.class), rs.getBigDecimal(8), rs.getString(9), rs.getString(10), rs.getInt(11),
                    rs.getString(12), rs.getBoolean(13), rs.getObject(14, UUID.class), rs.getString(15), rs.getBoolean(16)))
            .optional()
            .orElseThrow(() -> ApiException.validation(field + ".serviceCode", "Unknown service or tariff version"));

        if (!r.permitted()) {
            throw ApiException.forbidden("CBR-AUT-001", "Platform is not permitted to bill service " + serviceCode);
        }
        if (!"ACTIVE".equals(r.serviceStatus()) || !"ACTIVE".equals(r.tariffStatus()) || !r.inEffect()) {
            throw new ApiException(422, "CBR-TAR-001", "Tariff version " + tariffVersion + " of " + serviceCode
                    + " is inactive or outside its effective period");
        }
        if (r.mappingId() == null) {
            throw new ApiException(422, "CBR-TAR-002", "No active revenue mapping for " + serviceCode);
        }
        return new PricedTariff(r.serviceId(), r.code(), r.name(), r.scale(), r.requiresApproval(), r.tariffId(), r.unitPrice(),
                PaymentMode.valueOf(r.mode()), RoundingRule.valueOf(r.rounding()), r.maxValidity(), r.mappingId(), r.gfsCode());
    }
}
