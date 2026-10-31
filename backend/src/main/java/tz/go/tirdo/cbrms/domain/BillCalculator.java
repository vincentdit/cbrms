package tz.go.tirdo.cbrms.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Line and bill totals (Blueprint section 6). All money is BigDecimal; no floating point. */
public final class BillCalculator {
    private BillCalculator() {}

    public static BigDecimal lineTotal(BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal tax, RoundingRule rule) {
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unit price must not be negative");
        }
        BigDecimal d = discount == null ? BigDecimal.ZERO : discount;
        BigDecimal t = tax == null ? BigDecimal.ZERO : tax;
        BigDecimal raw = quantity.multiply(unitPrice).subtract(d).add(t);
        if (raw.signum() < 0) {
            throw new IllegalArgumentException("discount exceeds line value");
        }
        return rule.apply(raw);
    }

    public static BigDecimal lineTotal(BigDecimal quantity, BigDecimal unitPrice, RoundingRule rule) {
        return lineTotal(quantity, unitPrice, BigDecimal.ZERO, BigDecimal.ZERO, rule);
    }

    public static BigDecimal billTotal(List<BigDecimal> lineTotals) {
        return lineTotals.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** FR05a: quantity must not use more decimal places than the service allows. */
    public static boolean quantityScaleAllowed(BigDecimal quantity, int maxScale) {
        return quantity.stripTrailingZeros().scale() <= maxScale;
    }
}
