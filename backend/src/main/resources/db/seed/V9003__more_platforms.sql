-- LOCAL AND TEST ONLY: CIAP, TeLTP and ISICMS as source platforms, with sample services.
-- Services, prices, GFS and accounting codes are development placeholders, not approved tariffs.
INSERT INTO directorate (id, code, name) VALUES
  ('00000000-0000-7000-8000-000000000002', 'IAS', 'Industrial Advisory Services (development seed)'),
  ('00000000-0000-7000-8000-000000000003', 'TRN', 'Training and Capacity Building (development seed)'),
  ('00000000-0000-7000-8000-000000000004', 'INN', 'Innovation and Incubation (development seed)');

INSERT INTO service (id, code, name, name_sw, directorate_id, unit, quantity_scale, requires_approval) VALUES
  ('00000000-0000-7000-8000-000000000103', 'CIAP-ADV-001', 'Industrial advisory session', 'Kikao cha ushauri wa kiviwanda',
   '00000000-0000-7000-8000-000000000002', 'day', 0, false),
  ('00000000-0000-7000-8000-000000000104', 'CIAP-REV-002', 'Technical report review', 'Mapitio ya ripoti ya kitaalamu',
   '00000000-0000-7000-8000-000000000002', 'report', 0, true),
  ('00000000-0000-7000-8000-000000000105', 'TELTP-CRS-001', 'Short course fee: quality management (5 days)', 'Ada ya kozi fupi: usimamizi wa ubora (siku 5)',
   '00000000-0000-7000-8000-000000000003', 'participant', 0, false),
  ('00000000-0000-7000-8000-000000000106', 'ISICMS-INC-001', 'Incubation space', 'Nafasi ya uatamizi',
   '00000000-0000-7000-8000-000000000004', 'month', 0, false),
  ('00000000-0000-7000-8000-000000000107', 'ISICMS-LAB-002', 'Prototype workshop use', 'Matumizi ya karakana ya mfano',
   '00000000-0000-7000-8000-000000000004', 'hour', 0, true);

INSERT INTO tariff_version (service_id, version_no, currency, unit_price, payment_mode, rounding_rule, effective_period, approval_ref, status) VALUES
  ('00000000-0000-7000-8000-000000000103', 1, 'TZS', 350000.00, 'EXACT',   'WHOLE_SHILLING_HALF_UP', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-003', 'ACTIVE'),
  ('00000000-0000-7000-8000-000000000104', 1, 'TZS', 500000.00, 'PARTIAL', 'WHOLE_SHILLING_HALF_UP', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-004', 'ACTIVE'),
  ('00000000-0000-7000-8000-000000000105', 1, 'TZS', 450000.00, 'EXACT',   'WHOLE_SHILLING_HALF_UP', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-005', 'ACTIVE'),
  ('00000000-0000-7000-8000-000000000106', 1, 'TZS', 150000.00, 'PARTIAL', 'WHOLE_SHILLING_HALF_UP', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-006', 'ACTIVE'),
  ('00000000-0000-7000-8000-000000000107', 1, 'TZS',  25000.00, 'EXACT',   'WHOLE_SHILLING_HALF_UP', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-007', 'ACTIVE');

INSERT INTO revenue_mapping (service_id, gfs_code, accounting_code, effective_period, approval_ref, status) VALUES
  ('00000000-0000-7000-8000-000000000103', 'DEV-GFS-0003', 'DEV-ACC-0003', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-003', 'ACTIVE'),
  ('00000000-0000-7000-8000-000000000104', 'DEV-GFS-0003', 'DEV-ACC-0004', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-004', 'ACTIVE'),
  ('00000000-0000-7000-8000-000000000105', 'DEV-GFS-0005', 'DEV-ACC-0005', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-005', 'ACTIVE'),
  ('00000000-0000-7000-8000-000000000106', 'DEV-GFS-0006', 'DEV-ACC-0006', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-006', 'ACTIVE'),
  ('00000000-0000-7000-8000-000000000107', 'DEV-GFS-0006', 'DEV-ACC-0007', '[2026-01-01,2027-01-01)', 'DEV-APPROVAL-007', 'ACTIVE');

INSERT INTO source_system (id, code, name) VALUES
  ('00000000-0000-7000-8000-000000000202', 'ciap',   'Consultancy & Industrial Advisory Portal'),
  ('00000000-0000-7000-8000-000000000203', 'teltp',  'TIRDO e-Learning and Training Platform'),
  ('00000000-0000-7000-8000-000000000204', 'isicms', 'Innovation, Startup Incubation and Commercialization Management System');

-- Each platform may bill only its own services.
INSERT INTO source_system_service VALUES
  ('00000000-0000-7000-8000-000000000202', '00000000-0000-7000-8000-000000000103'),
  ('00000000-0000-7000-8000-000000000202', '00000000-0000-7000-8000-000000000104'),
  ('00000000-0000-7000-8000-000000000203', '00000000-0000-7000-8000-000000000105'),
  ('00000000-0000-7000-8000-000000000204', '00000000-0000-7000-8000-000000000106'),
  ('00000000-0000-7000-8000-000000000204', '00000000-0000-7000-8000-000000000107');

-- Machine identities: the plain name is the development (basic) user; service-account-* is the Keycloak client.
INSERT INTO app_user (subject, display_name, kind, source_system_id) VALUES
  ('ciap',                   'CIAP platform client',              'MACHINE', '00000000-0000-7000-8000-000000000202'),
  ('service-account-ciap',   'CIAP platform client (Keycloak)',   'MACHINE', '00000000-0000-7000-8000-000000000202'),
  ('teltp',                  'TeLTP platform client',             'MACHINE', '00000000-0000-7000-8000-000000000203'),
  ('service-account-teltp',  'TeLTP platform client (Keycloak)',  'MACHINE', '00000000-0000-7000-8000-000000000203'),
  ('isicms',                 'ISICMS platform client',            'MACHINE', '00000000-0000-7000-8000-000000000204'),
  ('service-account-isicms', 'ISICMS platform client (Keycloak)', 'MACHINE', '00000000-0000-7000-8000-000000000204');

-- Event delivery to each platform on the developer's machine (one port per platform).
INSERT INTO subscriber (source_system_id, endpoint_url, hmac_key_id) VALUES
  ('00000000-0000-7000-8000-000000000202', 'http://host.docker.internal:9091/cbrms/events', 'ciap-dev'),
  ('00000000-0000-7000-8000-000000000203', 'http://host.docker.internal:9092/cbrms/events', 'teltp-dev'),
  ('00000000-0000-7000-8000-000000000204', 'http://host.docker.internal:9093/cbrms/events', 'isicms-dev');
