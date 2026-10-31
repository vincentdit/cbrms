-- CBRMS baseline. Target: PostgreSQL 18 (native uuidv7()).
-- On PostgreSQL 13-17 (developer machines) a compatible public.uuidv7() is created.
CREATE EXTENSION IF NOT EXISTS btree_gist;

DO $do$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE p.proname = 'uuidv7' AND p.pronargs = 0) THEN
    EXECUTE $fn$
      CREATE FUNCTION public.uuidv7() RETURNS uuid LANGUAGE sql VOLATILE AS $body$
        SELECT encode(
                 set_bit(set_bit(
                   overlay(uuid_send(gen_random_uuid())
                           placing substring(int8send(floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3)
                           FROM 1 FOR 6),
                 52, 1), 53, 1),
               'hex')::uuid
      $body$
    $fn$;
  END IF;
END
$do$;

-- Generic guard: rows in append-only tables may never be updated or deleted.
CREATE FUNCTION cbrms_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'CBRMS: % on % is not permitted (append-only table)', TG_OP, TG_TABLE_NAME
    USING ERRCODE = 'restrict_violation';
END
$$;
