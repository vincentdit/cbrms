-- LOCAL AND TEST ONLY: a read-only auditor (Internal Audit).
INSERT INTO app_user (subject, display_name, kind) VALUES
  ('internal.auditor', 'Baraka Lyimo (internal auditor, dev)', 'HUMAN');
