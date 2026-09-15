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
-- Nor does it make every table unreadable to the owner. Four of the seven are
-- like accounts. api_keys and the two reconciliation tables carry policies that
-- allow a read with no tenant on purpose, key lookup happens before a tenant is
-- known and reconciliation has no tenant at all, so FORCE binds the owner there
-- too and the policy still lets it through. The gain is that the owner is
-- subject to the policies rather than above them.

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
-- INHERIT FALSE and SET FALSE keep it to administering the role. It matters
-- because nine-core authenticates as nine_app on that same cluster: measured,
-- with SET FALSE, SET ROLE nine_app is "permission denied to set role", while
-- V12's ALTER ROLE ... PASSWORD still returns ALTER ROLE. It is not a wall.
-- ADMIN is what V12 needs, and ADMIN is enough to add a second membership row
-- granting SET back, which was measured too. What this removes is the accident;
-- the deliberate version has to write a row in pg_auth_members to happen.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nine_app') THEN
        EXECUTE 'GRANT nine_app TO nine_owner WITH ADMIN OPTION, INHERIT FALSE, SET FALSE';
    END IF;
END $$;
