-- Runs once per database, as a superuser, before Flyway ever connects. It is
-- deliberately not a migration: Flyway connects as the role this file creates,
-- so the role has to exist first, and creating a role needs a superuser.
--
-- BI12.5. Nine tables were owned by postgres, a superuser, and seven of them
-- carry FORCE ROW LEVEL SECURITY. Measured on PostgreSQL 16, three rows in
-- accounts, no tenant bound: postgres reads three, a non-superuser owner reads
-- zero, and with a tenant bound it reads that tenant's two. The owner's escape
-- hatch fails closed too: under SET row_security = off the SELECT raises
-- rather than returning the rows.
-- Until the owner stops being a superuser, FORCE buys nothing.
--
-- This does not stop a superuser, and nothing inside the database can. What it
-- changes is that the credential the deployment hands Flyway is no longer one.
--
-- Nor does it make every table unreadable to the owner. Five of the seven are
-- like accounts. Two are not: api_keys, because a key is looked up before any
-- tenant is known (V4's key_lookup), and reconciliation_runs, because it holds
-- counts rather than tenant rows and V5 gave it USING (true) deliberately.
-- reconciliation_findings is not one of them, whatever a first reading of V4
-- suggests: V5 replaced that policy with findings_operator_only and said in its
-- own header that the comment claiming these two tables hold no tenant data was
-- wrong, because findings carries tenant_id. Measured, one row present, no
-- tenant bound: the owner reads 0 from findings and 244 from runs.
--
-- So the gain is that the owner is subject to the policies rather than above
-- them, which is not the same sentence as "the owner reads nothing".

-- The database this hands over is whichever one the connection is on, so it has
-- to be the right one. Measured: run against the cluster default and it hands
-- postgres to nine_owner, exits 0, prints nothing, and leaves the real database
-- untouched until something tries to migrate it. A refusal is cheaper.
DO $$
BEGIN
    IF current_database() IN ('postgres', 'template0', 'template1') THEN
        RAISE EXCEPTION 'bootstrap must run against the billing database, not %; name it in the connection string', current_database();
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nine_owner') THEN
        -- CREATEROLE because V4 and V9 create nine_app and nine_operator, and
        -- a role created here carries the ADMIN the later ALTER ROLE needs.
        -- The password is the development default; a deployment replaces it
        -- and passes the same value as NINE_MIGRATION_PASSWORD.
        CREATE ROLE nine_owner LOGIN
            PASSWORD 'nine_owner_dev'  -- nine:allow-secret, the development default
            NOSUPERUSER NOBYPASSRLS CREATEROLE;
    END IF;
END $$;

-- Ownership of the database, not a grant on the schema. Since PostgreSQL 15
-- public belongs to pg_database_owner, so this is what makes V4's
-- GRANT USAGE ON SCHEMA public a real grant. Measured with the database left
-- to postgres and only CREATE granted on the schema: that line warns "no
-- privileges were granted for public" and does nothing, and nine_app keeps
-- working only because PUBLIC holds USAGE by default. Revoking that default,
-- which is an ordinary hardening step, took nine_app straight to
-- "relation accounts does not exist".
DO $$
BEGIN
    EXECUTE format('ALTER DATABASE %I OWNER TO nine_owner', current_database());
END $$;

-- Roles are cluster wide, databases are not. Where nine_app already exists
-- because the compose stack or another service created it, nine_owner holds no
-- ADMIN on it and V12's ALTER ROLE ... PASSWORD fails with "permission denied
-- to alter role". A role nine_owner creates itself carries that ADMIN already,
-- so this fires only on a cluster that had nine_app first, which today means
-- the shared development stack and not a deployment.
--
-- INHERIT FALSE and SET FALSE narrow what the membership does day to day:
-- measured, SET ROLE nine_app is then "permission denied to set role" while
-- V12's ALTER ROLE ... PASSWORD still returns ALTER ROLE.
--
-- They are not a boundary, and the plain version of that is worth having in the
-- file. ADMIN plus CREATEROLE is the power to become the role: nine_owner can
-- set nine_app's password and log in as it, which reaches nine_core on a shared
-- cluster and writes nothing to pg_auth_members at all. It can also take that
-- role's login away. The fix for it is not here, it is one runtime role per
-- service, which is BI12.7.
--
-- nine_operator is granted the same way, and only because of where the role
-- came from. On a database this script bootstraps after a migration as postgres
-- both roles already exist and nine_owner administers neither; on a fresh one
-- V4 and V9 create them and the creator holds ADMIN already. Without this line
-- the two paths end with different memberships, and the first migration that
-- does for nine_operator what V12 did for nine_app passes on one and fails on
-- the other.
DO $$
DECLARE r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['nine_app', 'nine_operator'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('GRANT %I TO nine_owner WITH ADMIN OPTION, INHERIT FALSE, SET FALSE', r);
        END IF;
    END LOOP;
END $$;

-- Handing over the database does not hand over what is already in it. On a
-- database Flyway migrated as postgres, the next run as nine_owner takes
-- "permission denied for table flyway_schema_history" on the history insert and
-- "must be owner of table accounts" on the first ALTER, both measured. So the
-- objects move too, and this is the whole one-time step rather than half of it.
--
-- Not REASSIGN OWNED BY postgres: that also moves objects outside this schema
-- that the bootstrap superuser owns and needs. An explicit loop over public is
-- the narrow version. Indexes, constraints and triggers follow their table, so
-- they are not listed; partitions do not follow their parent, which is why
-- every relation is visited rather than only the top of each tree.
--
-- Identity sequences are skipped deliberately: an owned sequence cannot change
-- hands on its own, measured, "cannot change owner of sequence
-- reconciliation_runs_id_seq, linked to table reconciliation_runs". It follows
-- the table instead, which is why only free standing sequences are listed.
--
-- It moves everything in public that nine_owner does not already own, so a
-- deployment that has deliberately given an object to some third role has to
-- exclude it here rather than discover the change afterwards. Nothing in this
-- schema is in that position today.
--
-- On a fresh database this finds nothing, which is what makes it safe to leave
-- in a script that also runs before the first migration.
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT c.oid::regclass AS ident, c.relkind
          FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
           AND c.relowner <> 'nine_owner'::regrole
           AND NOT (c.relkind = 'S' AND EXISTS (
                     SELECT 1 FROM pg_depend d
                      WHERE d.classid = 'pg_class'::regclass
                        AND d.objid = c.oid AND d.deptype IN ('a', 'i')))
         ORDER BY (c.relkind = 'S')
    LOOP
        CASE r.relkind
            WHEN 'S' THEN EXECUTE format('ALTER SEQUENCE %s OWNER TO nine_owner', r.ident);
            WHEN 'v' THEN EXECUTE format('ALTER VIEW %s OWNER TO nine_owner', r.ident);
            WHEN 'm' THEN EXECUTE format('ALTER MATERIALIZED VIEW %s OWNER TO nine_owner', r.ident);
            WHEN 'f' THEN EXECUTE format('ALTER FOREIGN TABLE %s OWNER TO nine_owner', r.ident);
            ELSE            EXECUTE format('ALTER TABLE %s OWNER TO nine_owner', r.ident);
        END CASE;
    END LOOP;

    FOR r IN
        SELECT p.oid::regprocedure AS ident, p.prokind
          FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'public' AND p.proowner <> 'nine_owner'::regrole
    LOOP
        IF r.prokind = 'p' THEN
            EXECUTE format('ALTER PROCEDURE %s OWNER TO nine_owner', r.ident);
        ELSE
            EXECUTE format('ALTER FUNCTION %s OWNER TO nine_owner', r.ident);
        END IF;
    END LOOP;
END $$;
