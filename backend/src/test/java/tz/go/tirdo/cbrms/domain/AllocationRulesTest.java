package tz.go.tirdo.cbrms.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** AT03 and AT10: instalments, overpayment and exact-mode exceptions. */
class AllocationRulesTest {
    private static final BigDecimal TOTAL = new BigDecimal("60000.00");

    @Test
    void partialPaymentsInTwoInstalments() {
        var first = AllocationRules.decide(PaymentMode.PARTIAL, TOTAL, BigDecimal.ZERO, new BigDecimal("20000"));
        assertEquals(new BigDecimal("20000"), first.allocate());
        assertEquals(BillStates.Collection.PartiallyPaid, first.newState());
        assertFalse(first.exceptionRequired());

        var second = AllocationRules.decide(PaymentMode.PARTIAL, TOTAL, new BigDecimal("20000"), new BigDecimal("40000"));
        assertEquals(new BigDecimal("40000"), second.allocate());
        assertEquals(0, second.excess().signum());
        assertEquals(BillStates.Collection.Paid, second.newState());
    }

    @Test
    void fullModeOverpaymentBecomesCredit() {
        var d = AllocationRules.decide(PaymentMode.FULL, TOTAL, BigDecimal.ZERO, new BigDecimal("65000"));
        assertEquals(TOTAL, d.allocate());
        assertEquals(new BigDecimal("5000.00"), d.excess());
        assertEquals(BillStates.Collection.Overpaid, d.newState());
        assertFalse(d.exceptionRequired());
    }

    @Test
    void exactModeMismatchOpensException() {
        var d = AllocationRules.decide(PaymentMode.EXACT, TOTAL, BigDecimal.ZERO, new BigDecimal("50000"));
        assertTrue(d.exceptionRequired());
        assertEquals(BillStates.Collection.PartiallyPaid, d.newState());
    }

    @Test
    void secondPaymentOnPaidBillIsAllExcess() {
        var d = AllocationRules.decide(PaymentMode.EXACT, TOTAL, TOTAL, TOTAL);
        assertEquals(0, d.allocate().signum());
        assertEquals(TOTAL, d.excess());
        assertEquals(BillStates.Collection.Overpaid, d.newState());
        assertTrue(d.exceptionRequired());
    }

    @Test
    void ledgerState() {
        assertEquals(BillStates.Collection.Unpaid, AllocationRules.stateFromLedger(TOTAL, BigDecimal.ZERO, false));
        assertEquals(BillStates.Collection.PartiallyPaid, AllocationRules.stateFromLedger(TOTAL, BigDecimal.ONE, false));
        assertEquals(BillStates.Collection.Paid, AllocationRules.stateFromLedger(TOTAL, TOTAL, false));
        assertEquals(BillStates.Collection.Overpaid, AllocationRules.stateFromLedger(TOTAL, TOTAL, true));
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThrows(IllegalArgumentException.class, () -> AllocationRules.decide(PaymentMode.FULL, TOTAL, BigDecimal.ZERO, BigDecimal.ZERO));
    }
}
