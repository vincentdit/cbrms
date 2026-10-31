-- LOCAL AND TEST ONLY: the LIMS service account as Keycloak names it (client credentials grant).
INSERT INTO app_user (subject, display_name, kind, source_system_id) VALUES
  ('service-account-lims', 'LIMS platform client (Keycloak)', 'MACHINE', '00000000-0000-7000-8000-000000000201');
UPDATE app_user SET display_name = 'Asha Mwakyusa (billing officer, dev)' WHERE subject = 'finance.maker';
UPDATE app_user SET display_name = 'Neema Mushi (bill approver, dev)' WHERE subject = 'finance.approver';
