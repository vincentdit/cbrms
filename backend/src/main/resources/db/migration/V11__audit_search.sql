-- Audit search (FR28): who did what, to which record, when, and why.
CREATE INDEX audit_event_time ON audit_event (occurred_at DESC);
CREATE INDEX audit_event_actor ON audit_event (actor, occurred_at DESC);
CREATE INDEX audit_event_action ON audit_event (action, occurred_at DESC);
CREATE INDEX audit_event_correlation ON audit_event (correlation_id) WHERE correlation_id IS NOT NULL;
