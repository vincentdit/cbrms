package tz.go.tirdo.cbrms.domain;

/** The six independent state dimensions of a bill (Blueprint section 4). Names match database values. */
public final class BillStates {
    private BillStates() {}

    public enum Preparation { Draft, AwaitingApproval, Approved, Rejected, Superseded }

    public enum Registration {
        NotSubmitted, Submitted, AckUnknown, AwaitingControlNumber, Accepted, Rejected,
        CancellationPending, Cancelled, ExpiryChangePending;

        /** I2: a control number may be stored only in these states. */
        public boolean mayHoldControlNumber() {
            return this == Accepted || this == CancellationPending || this == Cancelled || this == ExpiryChangePending;
        }
    }

    public enum Collection { Unpaid, PartiallyPaid, Paid, Overpaid }

    public enum Reconciliation { NotDue, Pending, Matched, Exception, Resolved }

    public enum Release { Pending, Eligible, Released, Held }
}
