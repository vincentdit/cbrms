-- I2: a control number exists only once GePG has accepted the bill.
-- Checked at commit, so the bill and external_bill rows can be changed in either order.
CREATE FUNCTION cbrms_check_i2() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  bad       integer;
  v_bill_id uuid;
BEGIN
  IF TG_TABLE_NAME = 'bill' THEN
    v_bill_id := NEW.id;
  ELSE
    v_bill_id := NEW.bill_id;
  END IF;
  SELECT count(*) INTO bad
    FROM external_bill e JOIN bill b ON b.id = e.bill_id
   WHERE b.id = v_bill_id
     AND e.control_number IS NOT NULL
     AND b.registration_state NOT IN ('Accepted','CancellationPending','Cancelled','ExpiryChangePending');
  IF bad > 0 THEN
    RAISE EXCEPTION 'CBRMS invariant I2: control number stored while bill is not accepted by GePG'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER external_bill_i2 AFTER INSERT OR UPDATE ON external_bill
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION cbrms_check_i2();
CREATE CONSTRAINT TRIGGER bill_i2 AFTER UPDATE OF registration_state ON bill
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION cbrms_check_i2();

-- Nightly consistency job reads this view (rules that span rows or need case data).
CREATE VIEW v_invariant_violations AS
  SELECT 'I3' AS rule, b.id AS bill_id, 'Cancelled bill with money and no open case' AS detail
    FROM bill b
   WHERE b.registration_state = 'Cancelled' AND b.collection_state <> 'Unpaid'
     AND NOT EXISTS (SELECT 1 FROM exception_case c WHERE c.bill_id = b.id AND c.status <> 'CLOSED')
  UNION ALL
  SELECT 'I5', b.id, 'Superseded bill without a replacement'
    FROM bill b
   WHERE b.preparation_state = 'Superseded'
     AND NOT EXISTS (SELECT 1 FROM bill r WHERE r.replaces_bill_id = b.id)
  UNION ALL
  SELECT 'I6', b.id, 'Reconciliation NotDue although allocations exist'
    FROM bill b
   WHERE b.reconciliation_state = 'NotDue'
     AND EXISTS (SELECT 1 FROM payment_allocation a WHERE a.bill_id = b.id)
  UNION ALL
  SELECT 'BAL', b.id, 'Cached collection state differs from ledger'
    FROM bill b
    CROSS JOIN LATERAL (SELECT coalesce(sum(a.amount), 0) AS net FROM payment_allocation a WHERE a.bill_id = b.id) l
   WHERE b.collection_state <> CASE WHEN l.net = 0 THEN 'Unpaid'
                                    WHEN l.net < b.total THEN 'PartiallyPaid'
                                    WHEN EXISTS (SELECT 1 FROM credit_balance cb WHERE cb.bill_id = b.id) THEN 'Overpaid'
                                    ELSE 'Paid' END;
