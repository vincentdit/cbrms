-- Financial corrections (FR18, FR21, AT14): moving unallocated money to a bill and reversing an allocation.
-- Both are maker-checker; applying one appends ledger rows (never edits them) and records a financial_adjustment.
CREATE TABLE correction_request (
  id            uuid PRIMARY KEY DEFAULT uuidv7(),
  kind          text NOT NULL CHECK (kind IN ('ALLOCATE','REVERSE')),
  payment_id    uuid NOT NULL REFERENCES payment(id),
  bill_id       uuid NOT NULL REFERENCES bill(id),
  amount        numeric(20,2) NOT NULL CHECK (amount > 0),     -- at request time; re-checked when applied
  reason        text NOT NULL,
  case_id       uuid REFERENCES exception_case(id),
  status        text NOT NULL DEFAULT 'Requested' CHECK (status IN ('Requested','Applied','Rejected')),
  requested_by  uuid NOT NULL REFERENCES app_user(id),
  approval_id   uuid REFERENCES approval(id),
  adjustment_id uuid REFERENCES financial_adjustment(id),
  created_at    timestamptz NOT NULL DEFAULT now(),
  applied_at    timestamptz
);
-- One open correction per payment, so two officers cannot move the same money twice
CREATE UNIQUE INDEX correction_one_open_per_payment ON correction_request (payment_id) WHERE status = 'Requested';

ALTER TABLE exception_case DROP CONSTRAINT exception_case_type_check;
ALTER TABLE exception_case ADD CONSTRAINT exception_case_type_check CHECK (type IN
  ('UNMATCHED_PAYMENT','AMOUNT_MISMATCH','LATE_PAYMENT','PAYMENT_ON_CANCELLED_BILL','ACK_UNKNOWN','CONTROL_NUMBER_DELAY',
   'DELIVERY_FAILED','RECONCILIATION_DIFFERENCE','DISPUTE','SECURITY','CHANGE_OUTCOME_UNKNOWN','REFUND_FAILED',
   'REVERSAL_AFTER_RELEASE'));
