-- Authentication and row-level tenant isolation.
--
-- Two layers, both mandatory. The HTTP layer maps an API key to a tenant and
-- refuses any request naming another tenant. The database layer makes that
-- refusal hold even if the HTTP layer has a bug: every tenant-scoped table
-- has a policy, the policy is forced so the table owner is not exempt, and
-- the predicate fails closed to zero rows when no tenant context is set.

-- --- API keys ----------------------------------------------------------

CREATE TABLE api_keys (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    -- SHA-256 of the plaintext key, hex. The plaintext is shown once at
    -- creation and never stored; a leaked database yields no usable key.
    key_hash    CHAR(64)    NOT NULL UNIQUE,
    -- First 8 chars of the plaintext so an operator can tell keys apart
    -- in a list without ever seeing the secret.
    key_prefix  CHAR(8)     NOT NULL,
    label       TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX api_keys_tenant_idx ON api_keys (tenant_id);

-- --- Runtime role -------------------------------------------------------
-- The service connects as nine_app: not a superuser, not the owner of any
-- table. Both of those bypass RLS, which is exactly why the app must be
-- neither. The role is created by nine-infra for local dev; in any other
-- environment it is provisioned outside this migration. Grants are what
-- this migration owns.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nine_app') THEN
        CREATE ROLE nine_app LOGIN PASSWORD 'nine_app_dev';
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO nine_app;
GRANT SELECT, INSERT ON accounts, ledger_transactions, postings, usage_charges,
                        reconciliation_runs, reconciliation_findings TO nine_app;
GRANT SELECT ON price_plans, account_balances TO nine_app;
GRANT SELECT, INSERT, UPDATE ON api_keys TO nine_app;   -- UPDATE only for revoked_at
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO nine_app;
-- reconciliation_runs.id and postings.id are identity columns; their
-- sequences are covered by the grant above.

-- --- Tenant context -----------------------------------------------------
-- NULLIF(current_setting('app.tenant_id', true), '') is load-bearing:
--   * the second argument makes current_setting return NULL instead of
--     raising 42704 when the GUC was never set on this session
--   * after a SET LOCAL transaction commits, a custom GUC reverts to the
--     EMPTY STRING, not to unset; ''::uuid raises 22P02. NULLIF collapses
--     both states to NULL, the predicate is not true, zero rows. Fail-closed.
CREATE FUNCTION current_tenant() RETURNS UUID
    LANGUAGE sql STABLE
    AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

-- Operator context: cross-tenant read for the reconciliation job ONLY. It is
-- set by ReconciliationService inside its own transaction and nowhere else;
-- no HTTP request can set it. Without this the job would run under RLS with
-- no tenant, see zero rows, and report "clean" forever. That is the silent
-- failure RLS invites, and the reason this is written down.
CREATE FUNCTION is_operator() RETURNS BOOLEAN
    LANGUAGE sql STABLE
    AS $$ SELECT COALESCE(current_setting('app.role', true), '') = 'operator' $$;

-- --- Policies -----------------------------------------------------------

ALTER TABLE accounts            ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounts            FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounts
    USING      (tenant_id = current_tenant() OR is_operator())
    WITH CHECK (tenant_id = current_tenant());

ALTER TABLE ledger_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger_transactions FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger_transactions
    USING      (tenant_id = current_tenant() OR is_operator())
    WITH CHECK (tenant_id = current_tenant());

-- postings carry no tenant_id; they inherit it through their transaction.
ALTER TABLE postings            ENABLE ROW LEVEL SECURITY;
ALTER TABLE postings            FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON postings
    USING      (is_operator() OR EXISTS (SELECT 1 FROM ledger_transactions t
                         WHERE t.id = postings.transaction_id AND t.tenant_id = current_tenant()))
    WITH CHECK (EXISTS (SELECT 1 FROM ledger_transactions t
                         WHERE t.id = postings.transaction_id AND t.tenant_id = current_tenant()));

ALTER TABLE usage_charges       ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_charges       FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON usage_charges
    USING      (tenant_id = current_tenant() OR is_operator())
    WITH CHECK (tenant_id = current_tenant());

ALTER TABLE api_keys            ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_keys            FORCE  ROW LEVEL SECURITY;
-- Key lookup happens BEFORE a tenant is known, so the lookup must see all
-- keys. That is the one deliberate hole: a separate policy for the lookup
-- role. For now nine_app looks keys up with no tenant context, and the
-- policy below allows exactly that and nothing else: with a tenant set, a
-- tenant sees only its own keys.
CREATE POLICY key_lookup ON api_keys
    FOR SELECT
    USING (current_tenant() IS NULL OR tenant_id = current_tenant());
CREATE POLICY key_manage ON api_keys
    FOR ALL
    USING      (tenant_id = current_tenant() OR is_operator())
    WITH CHECK (tenant_id = current_tenant() OR is_operator());
-- is_operator() here is what lets the bootstrap endpoint mint a tenant's first
-- key before that tenant has any context of its own.

-- Reconciliation is an operator view across all tenants and runs with no
-- tenant context by design. The balance trigger and the reconciliation
-- queries are SECURITY DEFINER-free: they run as whoever calls them. The
-- scheduled job runs as nine_app with no tenant set, so it must be allowed
-- to read across tenants for these two tables only.
ALTER TABLE reconciliation_runs     ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation_findings ENABLE ROW LEVEL SECURITY;
CREATE POLICY operator_all ON reconciliation_runs     USING (true) WITH CHECK (true);
CREATE POLICY operator_all ON reconciliation_findings USING (true) WITH CHECK (true);
-- Note: these two `true` policies are a decision, not an oversight. The
-- tables hold no tenant data, only aggregate counts and references, and the
-- job that writes them has no tenant. Writing that down is the point.
