-- LOCAL AND TEST ONLY. Never enabled in production (see application-local.yml).
-- GFS and accounting codes below are placeholders for development, not approved mappings.
INSERT INTO directorate (id, code, name) VALUES
  ('00000000-0000-7000-8000-000000000001', 'LAB', 'Laboratory Services (development seed)');

INSERT INTO service (id, code, name, name_sw, directorate_id, unit, quantity_scale, requires_approval) VALUES
  ('00000000-0000-7000-8000-000000000101', 'LIMS-CHEM-001', 'Chemical analysis of water sample', 'Uchambuzi wa kemikali wa sampuli ya maji',
   '00000000-0000-7000-8000-000000000001', 'sample', 0, false),
  ('00000000-0000-7000-8000-000000000102', 'LIMS-MICRO-002', 'Microbiological analysis', 'Uchambuzi wa kibaiolojia',
   '00000000-0000-7000-8000-000000000001', 'sample', 0, true);

INSERT INTO tariff_version (service_id, version_no, currency, unit_price, payment_mode, rounding_rule, effective_period, approval_ref, status) VALUES
  ('00000000-0000-7000-8000-000000000101', 1, 'TZS', 45000.00, 'EXACT',   'WHOLE_SHILLING_HALF_UP', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-001', 'ACTIVE'),
  ('00000000-0000-7000-8000-000000000102', 1, 'TZS', 60000.00, 'PARTIAL', 'WHOLE_SHILLING_HALF_UP', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-002', 'ACTIVE');

INSERT INTO revenue_mapping (service_id, gfs_code, accounting_code, effective_period, approval_ref, status) VALUES
  ('00000000-0000-7000-8000-000000000101', 'DEV-GFS-0001', 'DEV-ACC-0001', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-001', 'ACTIVE'),
  ('00000000-0000-7000-8000-000000000102', 'DEV-GFS-0002', 'DEV-ACC-0002', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-002', 'ACTIVE');

INSERT INTO source_system (id, code, name) VALUES
  ('00000000-0000-7000-8000-000000000201', 'lims', 'Laboratory Information Management System');
INSERT INTO source_system_service VALUES
  ('00000000-0000-7000-8000-000000000201', '00000000-0000-7000-8000-000000000101'),
  ('00000000-0000-7000-8000-000000000201', '00000000-0000-7000-8000-000000000102');

INSERT INTO gepg_provider (id, code, sp_code, environment) VALUES
  ('00000000-0000-7000-8000-000000000301', 'SIM', 'SP-SIMULATOR', 'SIMULATOR');

INSERT INTO app_user (subject, display_name, kind, source_system_id) VALUES
  ('lims', 'LIMS platform client', 'MACHINE', '00000000-0000-7000-8000-000000000201');
INSERT INTO app_user (subject, display_name, kind, directorate_id) VALUES
  ('finance.maker',    'Finance billing officer (dev)', 'HUMAN', NULL),
  ('finance.approver', 'Finance bill approver (dev)',   'HUMAN', NULL);

INSERT INTO subscriber (source_system_id, endpoint_url, hmac_key_id) VALUES
  ('00000000-0000-7000-8000-000000000201', 'http://host.docker.internal:9090/cbrms/events', 'lims-dev');
