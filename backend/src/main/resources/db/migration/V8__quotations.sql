-- Quoted services (CIAP consultancy): the price is agreed per engagement, so it comes from a quotation that
-- Finance approves (maker-checker), not from a catalogue tariff. Platforms still never send amounts:
-- a bill line names an approved quotation and a milestone number, and CBRMS takes the milestone amount.

-- TARIFF: priced from tariff_version. QUOTED: the tariff version carries payment mode, rounding and validity
-- rules only (unit price 0); each line's price is the approved quotation milestone amount.
ALTER TABLE service ADD COLUMN pricing_basis text NOT NULL DEFAULT 'TARIFF' CHECK (pricing_basis IN ('TARIFF','QUOTED'));

CREATE TABLE quotation (
  id               uuid PRIMARY KEY DEFAULT uuidv7(),
  source_system_id uuid NOT NULL REFERENCES source_system(id),
  quote_reference  text NOT NULL,
  service_id       uuid NOT NULL REFERENCES service(id),
  payer_id         uuid NOT NULL REFERENCES payer(id),
  description      text NOT NULL,
  currency         char(3) NOT NULL DEFAULT 'TZS' CHECK (currency = 'TZS'),
  total            numeric(20,2) NOT NULL CHECK (total > 0),
  valid_until      date NOT NULL,
  status           text NOT NULL DEFAULT 'Requested' CHECK (status IN ('Requested','Approved','Rejected','Withdrawn')),
  requested_by     uuid NOT NULL REFERENCES app_user(id),
  approval_id      uuid REFERENCES approval(id),
  decided_at       timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (source_system_id, quote_reference)
);

CREATE TABLE quotation_milestone (
  id            uuid PRIMARY KEY DEFAULT uuidv7(),
  quotation_id  uuid NOT NULL REFERENCES quotation(id),
  milestone_no  integer NOT NULL CHECK (milestone_no >= 1),
  description   text NOT NULL,
  amount        numeric(20,2) NOT NULL CHECK (amount > 0),
  UNIQUE (quotation_id, milestone_no)
);
-- Milestones are the price evidence: once written they never change. A revised price is a new quotation.
CREATE TRIGGER quotation_milestone_append_only BEFORE UPDATE OR DELETE ON quotation_milestone
  FOR EACH ROW EXECUTE FUNCTION cbrms_append_only();

-- The approved amount, payer, service and platform of a quotation are frozen; only its status may move on.
CREATE FUNCTION quotation_terms_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.source_system_id <> OLD.source_system_id OR NEW.quote_reference <> OLD.quote_reference
     OR NEW.service_id <> OLD.service_id OR NEW.payer_id <> OLD.payer_id OR NEW.total <> OLD.total
     OR NEW.currency <> OLD.currency OR NEW.valid_until <> OLD.valid_until OR NEW.requested_by <> OLD.requested_by THEN
    RAISE EXCEPTION 'quotation % terms cannot change; issue a new quotation', OLD.quote_reference
      USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.status IN ('Rejected','Withdrawn') AND NEW.status <> OLD.status THEN
    RAISE EXCEPTION 'quotation % is closed', OLD.quote_reference USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER quotation_terms_frozen BEFORE UPDATE ON quotation FOR EACH ROW EXECUTE FUNCTION quotation_terms_frozen();
CREATE TRIGGER quotation_no_delete BEFORE DELETE ON quotation FOR EACH ROW EXECUTE FUNCTION cbrms_append_only();

-- A bill line priced from a quotation milestone
ALTER TABLE bill_line ADD COLUMN quotation_milestone_id uuid REFERENCES quotation_milestone(id);
CREATE INDEX bill_line_milestone ON bill_line (quotation_milestone_id) WHERE quotation_milestone_id IS NOT NULL;

ALTER TABLE approval DROP CONSTRAINT approval_subject_type_check;
ALTER TABLE approval ADD CONSTRAINT approval_subject_type_check CHECK (subject_type IN
  ('BILL','ADJUSTMENT','REFUND','TARIFF','ALLOCATION','EXPIRY_CHANGE','CANCELLATION','QUOTATION'));
