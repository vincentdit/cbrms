-- Cancellation and expiry-change requests (FR10, FR33). Each request is approved by a second officer (maker-checker).
CREATE TABLE bill_change_request (
  id              uuid PRIMARY KEY DEFAULT uuidv7(),
  bill_id         uuid NOT NULL REFERENCES bill(id),
  kind            text NOT NULL CHECK (kind IN ('CANCELLATION','EXPIRY_CHANGE')),
  status          text NOT NULL DEFAULT 'Requested' CHECK (status IN
                    ('Requested','Rejected','SentToGepg','OutcomeUnknown','Confirmed','RefusedByGepg')),
  reason          text NOT NULL,
  new_valid_until timestamptz,
  requested_by    uuid NOT NULL REFERENCES app_user(id),
  approval_id     uuid REFERENCES approval(id),
  gepg_code       text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  completed_at    timestamptz,
  CHECK (kind <> 'EXPIRY_CHANGE' OR new_valid_until IS NOT NULL)
);
-- one change in flight per bill
CREATE UNIQUE INDEX bill_change_one_open ON bill_change_request (bill_id)
  WHERE status IN ('Requested','SentToGepg','OutcomeUnknown');

-- Payer's language for notices and receipts (NFR08)
ALTER TABLE payer ADD COLUMN language char(2) NOT NULL DEFAULT 'sw' CHECK (language IN ('sw','en'));

-- Client notices: written in the same transaction as the event that causes them, then sent by a worker (FR31)
CREATE TABLE client_notification (
  id              uuid PRIMARY KEY DEFAULT uuidv7(),
  bill_id         uuid REFERENCES bill(id),
  channel         text NOT NULL CHECK (channel IN ('SMS','EMAIL')),
  recipient       text NOT NULL,
  template        text NOT NULL,
  language        char(2) NOT NULL,
  body            text NOT NULL,
  status          text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
  attempts        integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  lease_until     timestamptz,
  provider_ref    text,
  last_error      text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  sent_at         timestamptz
);
CREATE INDEX client_notification_due ON client_notification (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX client_notification_bill ON client_notification (bill_id, created_at);

-- One-time codes for the public bill lookup (FR32). Only a hash of the code is stored.
CREATE TABLE lookup_otp (
  id             uuid PRIMARY KEY DEFAULT uuidv7(),
  control_number text NOT NULL,
  code_hash      text NOT NULL,
  attempts       integer NOT NULL DEFAULT 0,
  expires_at     timestamptz NOT NULL,
  used_at        timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX lookup_otp_cn ON lookup_otp (control_number, created_at DESC);

-- Reconciliation run results kept with the batch for review and sign-off (FR22)
ALTER TABLE reconciliation_batch
  ADD COLUMN matched_count   integer NOT NULL DEFAULT 0,
  ADD COLUMN exception_count integer NOT NULL DEFAULT 0,
  ADD COLUMN missing_count   integer NOT NULL DEFAULT 0,
  ADD COLUMN external_total  numeric(20,2) NOT NULL DEFAULT 0,
  ADD COLUMN local_total     numeric(20,2) NOT NULL DEFAULT 0,
  ADD COLUMN review_note     text;

-- Refund and change approvals use the generic approval table
ALTER TABLE approval DROP CONSTRAINT approval_subject_type_check;
ALTER TABLE approval ADD CONSTRAINT approval_subject_type_check CHECK (subject_type IN
  ('BILL','ADJUSTMENT','REFUND','TARIFF','ALLOCATION','EXPIRY_CHANGE','CANCELLATION'));

ALTER TABLE refund_case ADD COLUMN reason text;
ALTER TABLE refund_case ADD COLUMN requested_by uuid REFERENCES app_user(id);
ALTER TABLE refund_case ADD COLUMN executed_at timestamptz;
ALTER TABLE refund_case ADD COLUMN failure_reason text;

-- The reconciliation exception type set gains "missing in GePG" and "missing locally"
ALTER TABLE exception_case DROP CONSTRAINT exception_case_type_check;
ALTER TABLE exception_case ADD CONSTRAINT exception_case_type_check CHECK (type IN
  ('UNMATCHED_PAYMENT','AMOUNT_MISMATCH','LATE_PAYMENT','PAYMENT_ON_CANCELLED_BILL','ACK_UNKNOWN','CONTROL_NUMBER_DELAY',
   'DELIVERY_FAILED','RECONCILIATION_DIFFERENCE','DISPUTE','SECURITY','CHANGE_OUTCOME_UNKNOWN','REFUND_FAILED'));
