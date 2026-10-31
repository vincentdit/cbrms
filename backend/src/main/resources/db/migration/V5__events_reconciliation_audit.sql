CREATE TABLE subscriber (
  id               uuid PRIMARY KEY DEFAULT uuidv7(),
  source_system_id uuid NOT NULL REFERENCES source_system(id),
  endpoint_url     text NOT NULL,
  hmac_key_id      text NOT NULL,           -- key material lives in the secret store, never in the database
  status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED'))
);
CREATE UNIQUE INDEX subscriber_one_active ON subscriber (source_system_id) WHERE status = 'ACTIVE';

CREATE TABLE outbox_event (
  id               uuid PRIMARY KEY DEFAULT uuidv7(),
  position         bigserial NOT NULL UNIQUE,   -- global cursor for GET /internal/v1/events
  aggregate_type   text NOT NULL,
  aggregate_id     uuid NOT NULL,
  source_system_id uuid NOT NULL REFERENCES source_system(id),
  sequence         bigint NOT NULL CHECK (sequence >= 1),
  event_type       text NOT NULL,
  schema_version   integer NOT NULL,
  payload          jsonb NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (aggregate_id, sequence)
);
CREATE INDEX outbox_event_source_position ON outbox_event (source_system_id, position);
CREATE TRIGGER outbox_append_only BEFORE UPDATE OR DELETE ON outbox_event
  FOR EACH ROW EXECUTE FUNCTION cbrms_append_only();

CREATE TABLE event_delivery (
  id               uuid PRIMARY KEY DEFAULT uuidv7(),
  event_id         uuid NOT NULL REFERENCES outbox_event(id),
  subscriber_id    uuid NOT NULL REFERENCES subscriber(id),
  status           text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DELIVERED','FAILED')),
  attempts         integer NOT NULL DEFAULT 0,
  next_attempt_at  timestamptz NOT NULL DEFAULT now(),
  lease_until      timestamptz,
  last_status_code integer,
  last_error       text,
  delivered_at     timestamptz,
  UNIQUE (event_id, subscriber_id)
);
CREATE INDEX event_delivery_due ON event_delivery (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE reconciliation_batch (
  id              uuid PRIMARY KEY DEFAULT uuidv7(),
  source          text NOT NULL CHECK (source IN ('GEPG','BANK')),
  batch_reference text NOT NULL,
  business_date   date NOT NULL,
  digest          text NOT NULL,
  status          text NOT NULL DEFAULT 'IMPORTED' CHECK (status IN ('IMPORTED','MATCHED','REVIEWED')),
  imported_at     timestamptz NOT NULL DEFAULT now(),
  reviewer_id     uuid REFERENCES app_user(id),
  reviewed_at     timestamptz,
  UNIQUE (source, digest),
  UNIQUE (source, batch_reference)
);

CREATE TABLE reconciliation_item (
  id                 uuid PRIMARY KEY DEFAULT uuidv7(),
  batch_id           uuid NOT NULL REFERENCES reconciliation_batch(id),
  external_reference text NOT NULL,
  control_number     text,
  currency           char(3) NOT NULL,
  amount             numeric(20,2) NOT NULL,
  occurred_at        timestamptz,
  match_status       text NOT NULL DEFAULT 'UNMATCHED' CHECK (match_status IN ('UNMATCHED','MATCHED','EXCEPTION')),
  payment_id         uuid REFERENCES payment(id),
  case_id            uuid REFERENCES exception_case(id)
);

CREATE TABLE audit_event (
  id             uuid PRIMARY KEY DEFAULT uuidv7(),
  actor          text NOT NULL,
  action         text NOT NULL,
  object_ref     text NOT NULL,
  reason         text,
  correlation_id text,
  details        jsonb,
  occurred_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_event_object ON audit_event (object_ref, occurred_at);
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON audit_event
  FOR EACH ROW EXECUTE FUNCTION cbrms_append_only();
