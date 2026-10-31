CREATE TABLE payer (
  id               uuid PRIMARY KEY DEFAULT uuidv7(),
  source_system_id uuid NOT NULL REFERENCES source_system(id),
  payer_reference  text NOT NULL,
  type             text NOT NULL DEFAULT 'INDIVIDUAL' CHECK (type IN ('INDIVIDUAL','ORGANISATION')),
  display_name     text NOT NULL,
  status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (source_system_id, payer_reference)
);

-- Restricted personal data (PDPA 2022): kept apart from reporting views.
CREATE TABLE payer_contact (
  id          uuid PRIMARY KEY DEFAULT uuidv7(),
  payer_id    uuid NOT NULL REFERENCES payer(id),
  channel     text NOT NULL CHECK (channel IN ('PHONE','EMAIL')),
  value       text NOT NULL,
  verified_at timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (payer_id, channel, value)
);

CREATE TABLE bill (
  id                    uuid PRIMARY KEY DEFAULT uuidv7(),
  source_system_id      uuid NOT NULL REFERENCES source_system(id),
  source_reference      text NOT NULL,
  liability_version     integer NOT NULL DEFAULT 1 CHECK (liability_version >= 1),
  payer_id              uuid NOT NULL REFERENCES payer(id),
  currency              char(3) NOT NULL CHECK (currency = 'TZS'),
  payment_mode          text NOT NULL CHECK (payment_mode IN ('EXACT','PARTIAL','FULL')),
  total                 numeric(20,2) NOT NULL CHECK (total >= 0),
  description           text NOT NULL,
  issued_at             timestamptz,
  due_date              date NOT NULL,
  valid_until           timestamptz NOT NULL,
  preparation_state     text NOT NULL CHECK (preparation_state IN
                          ('Draft','AwaitingApproval','Approved','Rejected','Superseded')),
  registration_state    text NOT NULL DEFAULT 'NotSubmitted' CHECK (registration_state IN
                          ('NotSubmitted','Submitted','AckUnknown','AwaitingControlNumber','Accepted','Rejected',
                           'CancellationPending','Cancelled','ExpiryChangePending')),
  collection_state      text NOT NULL DEFAULT 'Unpaid' CHECK (collection_state IN
                          ('Unpaid','PartiallyPaid','Paid','Overpaid')),
  reconciliation_state  text NOT NULL DEFAULT 'NotDue' CHECK (reconciliation_state IN
                          ('NotDue','Pending','Matched','Exception','Resolved')),
  release_state         text NOT NULL DEFAULT 'Pending' CHECK (release_state IN
                          ('Pending','Eligible','Released','Held')),
  release_exception_ref text,
  replaces_bill_id      uuid REFERENCES bill(id),
  created_by            uuid NOT NULL REFERENCES app_user(id),
  created_at            timestamptz NOT NULL DEFAULT now(),
  row_version           integer NOT NULL DEFAULT 0,
  UNIQUE (source_system_id, source_reference, liability_version),
  -- I1: unapproved bills are never registered with GePG
  CONSTRAINT bill_i1 CHECK (preparation_state NOT IN ('Draft','AwaitingApproval','Rejected')
                            OR registration_state = 'NotSubmitted'),
  -- I4: released only when paid or under an approved exception
  CONSTRAINT bill_i4 CHECK (release_state <> 'Released'
                            OR collection_state IN ('Paid','Overpaid')
                            OR release_exception_ref IS NOT NULL),
  CONSTRAINT bill_validity CHECK (valid_until > created_at)
);

-- BR03: one ACTIVE bill per source liability; replacements allowed (original Superseded/Cancelled first)
CREATE UNIQUE INDEX bill_one_active_liability ON bill (source_system_id, source_reference)
  WHERE preparation_state NOT IN ('Rejected','Superseded')
    AND registration_state NOT IN ('Rejected','Cancelled');
CREATE INDEX bill_payer_issued ON bill (payer_id, issued_at);
CREATE INDEX bill_aging ON bill (due_date) WHERE collection_state IN ('Unpaid','PartiallyPaid');
CREATE INDEX bill_submission_due ON bill (created_at)
  WHERE preparation_state = 'Approved' AND registration_state = 'NotSubmitted';

CREATE TABLE bill_line (
  id                 uuid PRIMARY KEY DEFAULT uuidv7(),
  bill_id            uuid NOT NULL REFERENCES bill(id),
  line_no            integer NOT NULL CHECK (line_no >= 1),
  service_id         uuid NOT NULL REFERENCES service(id),
  tariff_version_id  uuid NOT NULL REFERENCES tariff_version(id),
  revenue_mapping_id uuid NOT NULL REFERENCES revenue_mapping(id),
  gfs_code           text NOT NULL,
  description        text NOT NULL,
  quantity           numeric(18,4) NOT NULL CHECK (quantity > 0),
  unit_price         numeric(20,2) NOT NULL CHECK (unit_price >= 0),
  discount_amount    numeric(20,2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
  tax_amount         numeric(20,2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
  line_total         numeric(20,2) NOT NULL CHECK (line_total >= 0),
  UNIQUE (bill_id, line_no)
);
CREATE TRIGGER bill_line_append_only BEFORE UPDATE OR DELETE ON bill_line
  FOR EACH ROW EXECUTE FUNCTION cbrms_append_only();

-- Generic maker-checker record for bills, adjustments, refunds and tariffs
CREATE TABLE approval (
  id           uuid PRIMARY KEY DEFAULT uuidv7(),
  subject_type text NOT NULL CHECK (subject_type IN ('BILL','ADJUSTMENT','REFUND','TARIFF','ALLOCATION','EXPIRY_CHANGE','CANCELLATION')),
  subject_id   uuid NOT NULL,
  maker_id     uuid NOT NULL REFERENCES app_user(id),
  approver_id  uuid REFERENCES app_user(id),
  decision     text CHECK (decision IN ('APPROVED','REJECTED')),
  reason       text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  decided_at   timestamptz,
  CONSTRAINT approval_maker_checker CHECK (approver_id IS NULL OR approver_id <> maker_id),
  CONSTRAINT approval_complete CHECK ((decision IS NULL) = (approver_id IS NULL)
                                      AND (decision IS NULL) = (decided_at IS NULL))
);
CREATE UNIQUE INDEX approval_one_open ON approval (subject_type, subject_id) WHERE decision IS NULL;

CREATE TABLE external_bill (
  id                uuid PRIMARY KEY DEFAULT uuidv7(),
  bill_id           uuid NOT NULL UNIQUE REFERENCES bill(id),
  provider_id       uuid NOT NULL REFERENCES gepg_provider(id),
  gepg_bill_id      text NOT NULL,           -- stable identifier sent to GePG; reused only where replay rules allow
  control_number    text,
  expires_at        timestamptz NOT NULL,
  acknowledged_at   timestamptz,
  control_number_at timestamptz,
  UNIQUE (provider_id, gepg_bill_id),
  UNIQUE (provider_id, control_number)
);

CREATE TABLE submission_attempt (
  id               uuid PRIMARY KEY DEFAULT uuidv7(),
  external_bill_id uuid NOT NULL REFERENCES external_bill(id),
  kind             text NOT NULL CHECK (kind IN ('SUBMIT','ENQUIRY','CANCEL','EXPIRY_CHANGE')),
  request_digest   text NOT NULL,
  state            text NOT NULL CHECK (state IN ('SENT','ACKNOWLEDGED','REJECTED','UNKNOWN')),
  sent_at          timestamptz NOT NULL DEFAULT now(),
  response_code    text,
  response_digest  text
);

CREATE TABLE idempotency_record (
  client_id      text NOT NULL,
  operation      text NOT NULL,
  idem_key       text NOT NULL,
  request_digest text NOT NULL,
  resource_id    uuid NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  expires_at     timestamptz NOT NULL,
  PRIMARY KEY (client_id, operation, idem_key)
);
