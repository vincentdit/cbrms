CREATE TABLE integration_inbox (
  id                  uuid PRIMARY KEY DEFAULT uuidv7(),
  provider_id         uuid NOT NULL REFERENCES gepg_provider(id),
  message_type        text NOT NULL,
  external_message_id text NOT NULL,
  payload_digest      text NOT NULL,
  payload             text NOT NULL,          -- restricted: designated operators only
  verification_state  text NOT NULL CHECK (verification_state IN ('VERIFIED','REJECTED')),
  correlation_id      text,
  received_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (provider_id, message_type, external_message_id)
);
CREATE TRIGGER inbox_append_only BEFORE UPDATE OR DELETE ON integration_inbox
  FOR EACH ROW EXECUTE FUNCTION cbrms_append_only();

CREATE TABLE exception_case (
  id             uuid PRIMARY KEY DEFAULT uuidv7(),
  type           text NOT NULL CHECK (type IN ('UNMATCHED_PAYMENT','AMOUNT_MISMATCH','LATE_PAYMENT','PAYMENT_ON_CANCELLED_BILL',
                                               'ACK_UNKNOWN','CONTROL_NUMBER_DELAY','DELIVERY_FAILED','RECONCILIATION_DIFFERENCE',
                                               'DISPUTE','SECURITY')),
  status         text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','CLOSED')),
  owner_id       uuid REFERENCES app_user(id),
  reference      text,
  bill_id        uuid REFERENCES bill(id),
  value          numeric(20,2),
  currency       char(3),
  details        text,
  evidence_ref   text,
  due_date       date,
  opened_at      timestamptz NOT NULL DEFAULT now(),
  closed_at      timestamptz,
  closure_reason text,
  CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL AND closure_reason IS NOT NULL))
);
CREATE INDEX exception_case_workload ON exception_case (status, owner_id, opened_at);

CREATE TABLE payment (
  id                      uuid PRIMARY KEY DEFAULT uuidv7(),
  provider_id             uuid NOT NULL REFERENCES gepg_provider(id),
  external_transaction_id text NOT NULL,
  inbox_id                uuid NOT NULL REFERENCES integration_inbox(id),
  control_number          text NOT NULL,
  channel                 text,
  currency                char(3) NOT NULL,
  amount                  numeric(20,2) NOT NULL CHECK (amount > 0),
  occurred_at             timestamptz NOT NULL,
  received_at             timestamptz NOT NULL DEFAULT now(),
  -- one financial effect per GePG payment, whatever message type carried it
  UNIQUE (provider_id, external_transaction_id)
);
CREATE TRIGGER payment_append_only BEFORE UPDATE OR DELETE ON payment
  FOR EACH ROW EXECUTE FUNCTION cbrms_append_only();

CREATE TABLE payment_allocation (
  id                     uuid PRIMARY KEY DEFAULT uuidv7(),
  payment_id             uuid NOT NULL REFERENCES payment(id),
  bill_id                uuid NOT NULL REFERENCES bill(id),
  kind                   text NOT NULL CHECK (kind IN ('ALLOCATION','REVERSAL')),
  amount                 numeric(20,2) NOT NULL,
  reverses_allocation_id uuid REFERENCES payment_allocation(id),
  case_id                uuid REFERENCES exception_case(id),
  created_at             timestamptz NOT NULL DEFAULT now(),
  CHECK ((kind = 'ALLOCATION' AND amount > 0 AND reverses_allocation_id IS NULL)
      OR (kind = 'REVERSAL'   AND amount < 0 AND reverses_allocation_id IS NOT NULL))
);
CREATE INDEX payment_allocation_bill ON payment_allocation (bill_id);
CREATE TRIGGER allocation_append_only BEFORE UPDATE OR DELETE ON payment_allocation
  FOR EACH ROW EXECUTE FUNCTION cbrms_append_only();

-- Excess money, kept apart from bill revenue (FR16)
CREATE TABLE credit_balance (
  id         uuid PRIMARY KEY DEFAULT uuidv7(),
  payer_id   uuid REFERENCES payer(id),
  payment_id uuid NOT NULL REFERENCES payment(id),
  bill_id    uuid REFERENCES bill(id),
  amount     numeric(20,2) NOT NULL CHECK (amount > 0),
  status     text NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE','APPLIED','REFUNDED')),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE financial_adjustment (
  id                   uuid PRIMARY KEY DEFAULT uuidv7(),
  original_record_type text NOT NULL,
  original_record_id   uuid NOT NULL,
  type                 text NOT NULL CHECK (type IN ('REVERSAL','REALLOCATION','WRITE_OFF','CORRECTION')),
  amount               numeric(20,2) NOT NULL,
  approval_id          uuid NOT NULL REFERENCES approval(id),
  external_ref         text,
  created_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE refund_case (
  id                uuid PRIMARY KEY DEFAULT uuidv7(),
  payment_id        uuid NOT NULL REFERENCES payment(id),
  credit_balance_id uuid REFERENCES credit_balance(id),
  requested_amount  numeric(20,2) NOT NULL CHECK (requested_amount > 0),
  status            text NOT NULL DEFAULT 'Requested' CHECK (status IN
                      ('Requested','Reviewed','Approved','Executed','ExecutionFailed','Rejected')),
  approval_id       uuid REFERENCES approval(id),
  execution_ref     text,
  created_at        timestamptz NOT NULL DEFAULT now(),
  -- I7: Executed only with an execution reference
  CONSTRAINT refund_i7 CHECK (status <> 'Executed' OR execution_ref IS NOT NULL),
  CHECK (status NOT IN ('Approved','Executed','ExecutionFailed') OR approval_id IS NOT NULL)
);
