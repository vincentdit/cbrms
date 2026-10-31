CREATE TABLE directorate (
  id     uuid PRIMARY KEY DEFAULT uuidv7(),
  code   text NOT NULL UNIQUE,
  name   text NOT NULL,
  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE service (
  id                uuid PRIMARY KEY DEFAULT uuidv7(),
  code              text NOT NULL UNIQUE,
  name              text NOT NULL,
  name_sw           text,                                   -- Kiswahili wording for bills and notices
  directorate_id    uuid NOT NULL REFERENCES directorate(id),
  unit              text NOT NULL,
  quantity_scale    integer NOT NULL DEFAULT 0 CHECK (quantity_scale BETWEEN 0 AND 4),
  requires_approval boolean NOT NULL DEFAULT false,
  status            text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE tariff_version (
  id                uuid PRIMARY KEY DEFAULT uuidv7(),
  service_id        uuid NOT NULL REFERENCES service(id),
  version_no        integer NOT NULL CHECK (version_no >= 1),
  currency          char(3) NOT NULL CHECK (currency = 'TZS'),
  unit_price        numeric(20,2) NOT NULL CHECK (unit_price >= 0),
  payment_mode      text NOT NULL CHECK (payment_mode IN ('EXACT','PARTIAL','FULL')),
  rounding_rule     text NOT NULL CHECK (rounding_rule IN ('HALF_UP_2','WHOLE_SHILLING_HALF_UP')),
  tax_treatment     text NOT NULL DEFAULT 'NONE' CHECK (tax_treatment IN ('NONE','VAT_INCLUSIVE','VAT_EXCLUSIVE')),
  max_validity_days integer NOT NULL DEFAULT 180 CHECK (max_validity_days BETWEEN 1 AND 365),
  scope_code        text NOT NULL DEFAULT 'ALL',
  effective_period  daterange NOT NULL,
  approval_ref      text NOT NULL,
  status            text NOT NULL CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (service_id, version_no),
  EXCLUDE USING gist (service_id WITH =, currency WITH =, scope_code WITH =, effective_period WITH &&)
    WHERE (status = 'ACTIVE')
);

CREATE TABLE revenue_mapping (
  id               uuid PRIMARY KEY DEFAULT uuidv7(),
  service_id       uuid NOT NULL REFERENCES service(id),
  gfs_code         text NOT NULL,
  accounting_code  text NOT NULL,
  effective_period daterange NOT NULL,
  approval_ref     text NOT NULL,
  status           text NOT NULL CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
  EXCLUDE USING gist (service_id WITH =, effective_period WITH &&) WHERE (status = 'ACTIVE')
);

CREATE TABLE source_system (
  id                   uuid PRIMARY KEY DEFAULT uuidv7(),
  code                 text NOT NULL UNIQUE,
  name                 text NOT NULL,
  status               text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED')),
  credential_reference text
);

-- FR03: each platform may bill only its permitted services
CREATE TABLE source_system_service (
  source_system_id uuid NOT NULL REFERENCES source_system(id),
  service_id       uuid NOT NULL REFERENCES service(id),
  PRIMARY KEY (source_system_id, service_id)
);

CREATE TABLE gepg_provider (
  id          uuid PRIMARY KEY DEFAULT uuidv7(),
  code        text NOT NULL UNIQUE,
  sp_code     text NOT NULL,
  sub_sp_code text,
  system_id   text,
  environment text NOT NULL CHECK (environment IN ('SIMULATOR','TEST','PRODUCTION')),
  status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE'))
);

-- Mirrors identities held in Keycloak (staff and machine clients). Roles stay in Keycloak.
CREATE TABLE app_user (
  id               uuid PRIMARY KEY DEFAULT uuidv7(),
  subject          text NOT NULL UNIQUE,
  display_name     text NOT NULL,
  kind             text NOT NULL CHECK (kind IN ('HUMAN','MACHINE','SYSTEM')),
  directorate_id   uuid REFERENCES directorate(id),
  source_system_id uuid REFERENCES source_system(id),
  status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  CHECK (kind <> 'MACHINE' OR source_system_id IS NOT NULL)
);

-- Actor recorded for work done by CBRMS's own scheduled workers
INSERT INTO app_user (id, subject, display_name, kind)
VALUES ('00000000-0000-7000-8000-0000000000aa', 'cbrms-system', 'CBRMS system worker', 'SYSTEM');
