-- BI12.4. Operator stops being a variable the application can set.
--
-- is_operator() read current_setting('app.role'). app.role is a custom
-- placeholder GUC, and Postgres lets any role set one, so the runtime role
-- decided for itself whether it was an operator. Measured on 2026-08-31:
-- connected as nine_app, with rolsuper, rolcreaterole and rolbypassrls all
-- false, one set_config call turned a read of reconciliation_findings from 0
-- rows into every row in the table.
--
-- The alternative considered was a SECURITY DEFINER function. It was rejected
-- on measurement, not taste: with a non-superuser owner, FORCE ROW LEVEL
-- SECURITY binds the definer too and the function returns an empty set rather
-- than an error, which a reconciliation job reports as "we checked and found
-- nothing". Making it work at all requires an owner that bypasses RLS, and
-- then every function that role will ever own is part of the boundary. The
-- full comparison, with both escape hatches measured, is in
-- docs/artifacts/2026-08-31-operator-is-not-a-guc.md.
--
-- A role fails closed. A job that forgets the operator connection reads zero
-- rows and the report is visibly empty. The other shape fails open.

-- The password is a Flyway placeholder, not a literal. V4 hardcodes nine_app's
-- password and that is BI16.3, still open; a second literal here would double
-- the debt this migration would otherwise have to clean up twice. The default
-- lives in application.yml so a fresh checkout still works, and a deployment
-- supplies NINE_OPERATOR_PASSWORD. This is also the mechanism BI16.3 can lift
-- for nine_app without inventing anything.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nine_operator') THEN
        CREATE ROLE nine_operator LOGIN PASSWORD '${operatorPassword}';
    END IF;
END $$;

-- Narrower than nine_app, deliberately. The operator paths are reconciliation
-- and minting a tenant's first API key, and between them they touch five
-- tables. accounts, price_plans and account_balances are not among them, so
-- the role that may read across every tenant may not read the tenant's books.
GRANT USAGE ON SCHEMA public TO nine_operator;
GRANT SELECT ON ledger_transactions, postings, usage_charges,
                reconciliation_runs, reconciliation_findings TO nine_operator;
GRANT INSERT ON reconciliation_runs, reconciliation_findings TO nine_operator;
GRANT SELECT, INSERT ON api_keys TO nine_operator;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO nine_operator;

-- The whole change, in one function body. Every policy in V4 and V5 that says
-- "OR is_operator()" now asks who is connected instead of what the connection
-- claims about itself. current_user is not settable: nine_app cannot SET ROLE
-- to a role it holds no membership in, and cannot grant itself that membership
-- without ADMIN OPTION. Both refusals were measured.
CREATE OR REPLACE FUNCTION is_operator() RETURNS BOOLEAN
    LANGUAGE sql STABLE
    AS $$ SELECT current_user = 'nine_operator' $$;

-- app.role is left in place rather than dropped. It is a custom GUC with no
-- catalog entry to drop, and nothing reads it after this migration. The
-- application stops writing it in the same change.
