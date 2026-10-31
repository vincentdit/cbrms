-- LOCAL AND TEST ONLY: a quoted consultancy service for CIAP. Placeholder codes, not approved mappings.
INSERT INTO service (id, code, name, name_sw, directorate_id, unit, quantity_scale, requires_approval, pricing_basis) VALUES
  ('00000000-0000-7000-8000-000000000108', 'CIAP-CONS-003', 'Consultancy assignment (quoted)', 'Kazi ya ushauri (kwa nukuu ya bei)',
   '00000000-0000-7000-8000-000000000002', 'milestone', 0, false, 'QUOTED');

-- Rules only: unit price 0, the quotation milestone supplies the price
INSERT INTO tariff_version (service_id, version_no, currency, unit_price, payment_mode, rounding_rule, effective_period, approval_ref, status) VALUES
  ('00000000-0000-7000-8000-000000000108', 1, 'TZS', 0.00, 'EXACT', 'WHOLE_SHILLING_HALF_UP', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-008', 'ACTIVE');

INSERT INTO revenue_mapping (service_id, gfs_code, accounting_code, effective_period, approval_ref, status) VALUES
  ('00000000-0000-7000-8000-000000000108', 'DEV-GFS-0003', 'DEV-ACC-0008', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-008', 'ACTIVE');

INSERT INTO source_system_service VALUES
  ('00000000-0000-7000-8000-000000000202', '00000000-0000-7000-8000-000000000108');
