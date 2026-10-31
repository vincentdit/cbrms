package tz.go.tirdo.cbrms.domain;

import java.math.BigDecimal;

/**
 * Decides how a verified payment is applied to a bill (Blueprint sections 5 and 6, FR15, FR16).
 * Outstanding = max(total - netAllocated, 0); excess is never counted as bill revenue.
 */
public final class AllocationRules {
    private AllocationRules() {}

    public record Decision(BigDecimal allocate, BigDecimal excess, BillStates.Collection newState, boolean exceptionRequired, String exceptionReason) {}

    public static Decision decide(PaymentMode mode, BigDecimal billTotal, BigDecimal netAllocated, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("payment amount must be positive");
        }
        BigDecimal outstanding = billTotal.subtract(netAllocated).max(BigDecimal.ZERO);
        BigDecimal allocate = amount.min(outstanding);
        BigDecimal excess = amount.subtract(allocate);
        BigDecimal newNet = netAllocated.add(allocate);

        BillStates.Collection state;
        if (excess.signum() > 0) {
            state = BillStates.Collection.Overpaid;
        } else if (newNet.signum() == 0) {
            state = BillStates.Collection.Unpaid;
        } else if (newNet.compareTo(billTotal) < 0) {
            state = BillStates.Collection.PartiallyPaid;
        } else {
            state = BillStates.Collection.Paid;
        }

        boolean exception = false;
        String reason = null;
        if (mode == PaymentMode.EXACT && amount.compareTo(billTotal) != 0) {
            exception = true;
            reason = "Exact-mode bill received " + amount.toPlainString() + " against total " + billTotal.toPlainString();
        } else if (mode == PaymentMode.EXACT && netAllocated.signum() > 0) {
            exception = true;
            reason = "Exact-mode bill already paid; second payment received";
        } else if (mode == PaymentMode.PARTIAL && excess.signum() > 0) {
            exception = true;
            reason = "Partial-mode bill overpaid by " + excess.toPlainString();
        }
        return new Decision(allocate, excess, state, exception, reason);
    }

    /** Collection state from the ledger, used by the consistency check. */
    public static BillStates.Collection stateFromLedger(BigDecimal billTotal, BigDecimal netAllocated, boolean hasCredit) {
        if (netAllocated.signum() == 0) {
            return BillStates.Collection.Unpaid;
        }
        if (netAllocated.compareTo(billTotal) < 0) {
            return BillStates.Collection.PartiallyPaid;
        }
        return hasCredit ? BillStates.Collection.Overpaid : BillStates.Collection.Paid;
    }
}
