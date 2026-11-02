-- LOCAL AND TEST ONLY: the system administrator. Sees the whole console; does not prepare or approve money.
INSERT INTO app_user (subject, display_name, kind) VALUES
  ('admin', 'System administrator (dev)', 'HUMAN');
