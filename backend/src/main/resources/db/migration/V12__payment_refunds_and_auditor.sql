-- Refunds of unallocated money (no bill and no credit balance), for example a suspense payment the payer asks back.
ALTER TABLE refund_case ADD COLUMN case_id uuid REFERENCES exception_case(id);
ALTER TABLE refund_case ADD COLUMN payee_name text;
ALTER TABLE refund_case ADD COLUMN payee_account text;      -- bank account or mobile money number; masked in listings
ALTER TABLE refund_case ADD CONSTRAINT refund_payee_for_unallocated
  CHECK (credit_balance_id IS NOT NULL OR (payee_name IS NOT NULL AND payee_account IS NOT NULL));

-- The one definition of "unallocated": money received that is not applied to a bill, not held as credit and not
-- committed to a refund. Refunds of unallocated money reserve it from the moment they are requested until rejected.
CREATE VIEW v_payment_unallocated AS
SELECT p.id AS payment_id,
       p.amount
       - coalesce((SELECT sum(a.amount) FROM payment_allocation a WHERE a.payment_id = p.id), 0)
       - coalesce((SELECT sum(c.amount) FROM credit_balance c WHERE c.payment_id = p.id), 0)
       - coalesce((SELECT sum(r.requested_amount) FROM refund_case r
                    WHERE r.payment_id = p.id AND r.credit_balance_id IS NULL AND r.status <> 'Rejected'), 0) AS unallocated
  FROM payment p;
