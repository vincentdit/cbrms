-- Operations monitoring (NFR09, FR27e). Jobs outside the application (backups, restore drills) report in here,
-- so CBRMS can alert when one is late. Rows are only ever added.
CREATE TABLE ops_heartbeat (
  id          uuid PRIMARY KEY DEFAULT uuidv7(),
  name        text NOT NULL CHECK (name ~ '^[a-z][a-z0-9_.-]{2,50}$'),   -- for example backup.full, backup.diff, restore.drill
  ok          boolean NOT NULL DEFAULT true,
  detail      text,
  occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ops_heartbeat_latest ON ops_heartbeat (name, occurred_at DESC);
CREATE TRIGGER ops_heartbeat_append_only BEFORE UPDATE OR DELETE ON ops_heartbeat
  FOR EACH ROW EXECUTE FUNCTION cbrms_append_only();

-- The nightly invariant check opens a case for each violation it finds
ALTER TABLE exception_case DROP CONSTRAINT exception_case_type_check;
ALTER TABLE exception_case ADD CONSTRAINT exception_case_type_check CHECK (type IN
  ('UNMATCHED_PAYMENT','AMOUNT_MISMATCH','LATE_PAYMENT','PAYMENT_ON_CANCELLED_BILL','ACK_UNKNOWN','CONTROL_NUMBER_DELAY',
   'DELIVERY_FAILED','RECONCILIATION_DIFFERENCE','DISPUTE','SECURITY','CHANGE_OUTCOME_UNKNOWN','REFUND_FAILED',
   'REVERSAL_AFTER_RELEASE','INVARIANT_VIOLATION'));
