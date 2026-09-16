-- Runs before every migrate, and that is the whole point of it being here
-- rather than in a versioned migration. BI12.5.
--
-- A V14 that checks this would check once. Set NINE_MIGRATION_USERNAME back to
-- postgres afterwards and every table the next migration creates belongs to a
-- superuser again, with nothing failing and nothing said. Flyway fires
-- BEFORE_MIGRATE at the top of every migrate() call, pending migrations or not,
-- so a callback is the only shape of this check that cannot be outlived.
--
-- The subject is the table owner, not current_user. A database that was
-- bootstrapped but never had its objects moved connects as nine_owner and still
-- has seven FORCE tables owned by postgres, measured: current_user alone reports
-- that database as healthy. Both are checked, in the order that gives the more
-- useful message first.
DO $$
DECLARE exposed bigint;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles
                WHERE rolname = current_user AND (rolsuper OR rolbypassrls)) THEN
        RAISE EXCEPTION
            'nine-billing migrates as a non-superuser owner, and % bypasses row level security: set NINE_MIGRATION_USERNAME to nine_owner',
            current_user;
    END IF;

    -- Not scoped to public on purpose: a table that declares FORCE anywhere and
    -- belongs to a role that ignores it is the condition this exists to catch.
    SELECT count(*) INTO exposed
      FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner
     WHERE c.relforcerowsecurity AND (r.rolsuper OR r.rolbypassrls);

    IF exposed > 0 THEN
        RAISE EXCEPTION
            '% tables carry FORCE ROW LEVEL SECURITY and belong to a role that bypasses it: run db/bootstrap.sql against this database',
            exposed;
    END IF;
END $$;
