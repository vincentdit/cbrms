package tz.go.tirdo.cbrms.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** AT01 and FR06: Finance-approved rounding vectors. */
class BillCalculatorTest {

    @Test
    void wholeShillingRoundsHalfUp() {
        assertEquals(new BigDecimal("1001.00"), BillCalculator.lineTotal(new BigDecimal("3"), new BigDecimal("333.50"), RoundingRule.WHOLE_SHILLING_HALF_UP));
        assertEquals(new BigDecimal("1000.00"), BillCalculator.lineTotal(new BigDecimal("3"), new BigDecimal("333.33"), RoundingRule.WHOLE_SHILLING_HALF_UP));
    }

    @Test
    void twoDecimalRoundsHalfUp() {
        assertEquals(new BigDecimal("0.13"), BillCalculator.lineTotal(new BigDecimal("0.5"), new BigDecimal("0.25"), RoundingRule.HALF_UP_2));
        assertEquals(new BigDecimal("90000.00"), BillCalculator.lineTotal(new BigDecimal("2"), new BigDecimal("45000"), RoundingRule.HALF_UP_2));
    }

    @Test
    void discountAndTaxApplyBeforeRounding() {
        assertEquals(new BigDecimal("44100.00"),
                BillCalculator.lineTotal(new BigDecimal("1"), new BigDecimal("45000"), new BigDecimal("1000"), new BigDecimal("100.40"), RoundingRule.WHOLE_SHILLING_HALF_UP));
    }

    @Test
    void billTotalSumsLines() {
        assertEquals(new BigDecimal("105000.00"), BillCalculator.billTotal(List.of(new BigDecimal("45000.00"), new BigDecimal("60000.00"))));
        assertEquals(new BigDecimal("0.00"), BillCalculator.billTotal(List.of()));
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> BillCalculator.lineTotal(BigDecimal.ZERO, BigDecimal.TEN, RoundingRule.HALF_UP_2));
        assertThrows(IllegalArgumentException.class, () -> BillCalculator.lineTotal(BigDecimal.ONE, new BigDecimal("-1"), RoundingRule.HALF_UP_2));
        assertThrows(IllegalArgumentException.class,
                () -> BillCalculator.lineTotal(BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("11"), BigDecimal.ZERO, RoundingRule.HALF_UP_2));
    }

    @Test
    void quantityScale() {
        assertTrue(BillCalculator.quantityScaleAllowed(new BigDecimal("2.000"), 0));
        assertFalse(BillCalculator.quantityScaleAllowed(new BigDecimal("2.5"), 0));
        assertTrue(BillCalculator.quantityScaleAllowed(new BigDecimal("2.25"), 2));
    }
}
