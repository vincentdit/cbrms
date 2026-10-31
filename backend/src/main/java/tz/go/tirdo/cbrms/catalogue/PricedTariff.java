package tz.go.tirdo.cbrms.catalogue;

import java.math.BigDecimal;
import java.util.UUID;
import tz.go.tirdo.cbrms.domain.PaymentMode;
import tz.go.tirdo.cbrms.domain.RoundingRule;

/** A service and tariff version resolved for billing on a given date. */
public record PricedTariff(
        UUID serviceId, String serviceCode, String serviceName, int quantityScale, boolean requiresApproval,
        UUID tariffVersionId, BigDecimal unitPrice, PaymentMode paymentMode, RoundingRule roundingRule, int maxValidityDays,
        UUID revenueMappingId, String gfsCode) {}
