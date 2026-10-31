-- Catalogue changes (FR01): Finance proposes, a second officer approves, CBRMS applies. Nothing in the
-- catalogue changes without an approved proposal, and prices are never backdated.
CREATE TABLE catalogue_change (
  id                uuid PRIMARY KEY DEFAULT uuidv7(),
  kind              text NOT NULL CHECK (kind IN ('NEW_SERVICE','NEW_TARIFF','NEW_REVENUE_MAPPING','SERVICE_UPDATE')),
  service_code      text NOT NULL,
  status            text NOT NULL DEFAULT 'Requested' CHECK (status IN ('Requested','Applied','Rejected')),
  reason            text NOT NULL,
  approval_ref      text,                      -- Finance authority for the price or mapping (for example a DG approval)
  -- service fields (NEW_SERVICE, SERVICE_UPDATE)
  name              text,
  name_sw           text,
  directorate_id    uuid REFERENCES directorate(id),
  unit              text,
  quantity_scale    integer CHECK (quantity_scale BETWEEN 0 AND 4),
  requires_approval boolean,
  pricing_basis     text CHECK (pricing_basis IN ('TARIFF','QUOTED')),
  service_status    text CHECK (service_status IN ('ACTIVE','INACTIVE')),
  platform_codes    text[],
  -- tariff fields (NEW_SERVICE, NEW_TARIFF)
  unit_price        numeric(20,2) CHECK (unit_price >= 0),
  payment_mode      text CHECK (payment_mode IN ('EXACT','PARTIAL','FULL')),
  rounding_rule     text CHECK (rounding_rule IN ('HALF_UP_2','WHOLE_SHILLING_HALF_UP')),
  max_validity_days integer CHECK (max_validity_days BETWEEN 1 AND 365),
  -- revenue mapping fields (NEW_SERVICE, NEW_REVENUE_MAPPING)
  gfs_code          text,
  accounting_code   text,
  -- when the new price or mapping takes effect (inclusive) and, optionally, ends (exclusive)
  effective_from    date,
  effective_to      date,
  requested_by      uuid NOT NULL REFERENCES app_user(id),
  approval_id       uuid REFERENCES approval(id),
  applied_at        timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from),
  CHECK (kind <> 'NEW_TARIFF' OR (unit_price IS NOT NULL AND payment_mode IS NOT NULL AND rounding_rule IS NOT NULL
                                  AND max_validity_days IS NOT NULL AND effective_from IS NOT NULL AND approval_ref IS NOT NULL)),
  CHECK (kind <> 'NEW_REVENUE_MAPPING' OR (gfs_code IS NOT NULL AND accounting_code IS NOT NULL
                                           AND effective_from IS NOT NULL AND approval_ref IS NOT NULL))
);
-- One open proposal per service at a time, so two officers cannot propose conflicting changes
CREATE UNIQUE INDEX catalogue_change_one_open ON catalogue_change (service_code) WHERE status = 'Requested';

-- Traceability: which approved proposal created each price and mapping
ALTER TABLE tariff_version ADD COLUMN catalogue_change_id uuid REFERENCES catalogue_change(id);
ALTER TABLE revenue_mapping ADD COLUMN catalogue_change_id uuid REFERENCES catalogue_change(id);

ALTER TABLE approval DROP CONSTRAINT approval_subject_type_check;
ALTER TABLE approval ADD CONSTRAINT approval_subject_type_check CHECK (subject_type IN
  ('BILL','ADJUSTMENT','REFUND','TARIFF','ALLOCATION','EXPIRY_CHANGE','CANCELLATION','QUOTATION','CATALOGUE'));
