-- Double-entry ledger.
--
-- Three rules are enforced by the database rather than by application code,
-- because application code is one refactor away from forgetting them:
--   1. money is stored in minor units as BIGINT, never as a floating type
--   2. every posting belongs to a transaction whose debits equal its credits
--   3. nothing is ever updated or deleted; a correction is a new reversing
--      transaction that points at the one it reverses

CREATE TABLE accounts (
    id           UUID PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    code         TEXT        NOT NULL,
    -- ASSET and EXPENSE increase on the debit side, the rest on the credit
    -- side. Storing the normal side lets the balance view sign correctly
    -- without a CASE per account type scattered through the queries.
    type         TEXT        NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE')),
    currency     CHAR(3)     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT accounts_tenant_code_unique UNIQUE (tenant_id, code),
    -- Target for the composite foreign key from postings, which is what stops
    -- a GBP posting from landing on a USD account.
    CONSTRAINT accounts_id_currency_unique UNIQUE (id, currency)
);

CREATE INDEX accounts_tenant_idx ON accounts (tenant_id);

-- A transaction is the unit that must balance. It is immutable once written.
CREATE TABLE ledger_transactions (
    id              UUID PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    -- The caller's own key for this operation. A retry of the same logical
    -- charge carries the same key, and the unique constraint below is what
    -- actually makes the endpoint idempotent: the second insert fails on the
    -- constraint rather than on an application-level check that races.
    idempotency_key TEXT        NOT NULL,
    description     TEXT        NOT NULL,
    -- Set only on a reversing transaction, pointing at what it reverses.
    -- Corrections are new rows; history is never rewritten.
    reverses_id     UUID        REFERENCES ledger_transactions (id),
    occurred_at     TIMESTAMPTZ NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ledger_tx_tenant_idem_unique UNIQUE (tenant_id, idempotency_key),
    -- A transaction cannot reverse itself.
    CONSTRAINT ledger_tx_no_self_reverse CHECK (reverses_id IS DISTINCT FROM id)
);

CREATE INDEX ledger_tx_tenant_occurred_idx ON ledger_transactions (tenant_id, occurred_at DESC);
-- Partial: only reversing rows carry the column, so the index stays small.
CREATE UNIQUE INDEX ledger_tx_reverses_unique ON ledger_transactions (reverses_id)
    WHERE reverses_id IS NOT NULL;

-- One side of one transaction. Positive amount only; direction lives in the
-- `direction` column so a sign error cannot silently invert an entry.
CREATE TABLE postings (
    id             BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id UUID    NOT NULL REFERENCES ledger_transactions (id),
    account_id     UUID    NOT NULL REFERENCES accounts (id),
    direction      TEXT    NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    -- Minor units. 1234 with currency GBP means 12.34 GBP. Never a float:
    -- 0.1 + 0.2 is not 0.3 in binary floating point, and a ledger that cannot
    -- add up is not a ledger.
    amount_minor   BIGINT  NOT NULL CHECK (amount_minor > 0),
    currency       CHAR(3) NOT NULL,

    -- Composite FK, not a plain one. A posting must carry the same currency as
    -- the account it lands on, and the database is what guarantees it. Without
    -- this you can post GBP onto a USD account and the imbalance never shows,
    -- because the balance trigger only sums minor units.
    CONSTRAINT postings_currency_matches_account
        FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
);

CREATE INDEX postings_account_idx ON postings (account_id);
CREATE INDEX postings_transaction_idx ON postings (transaction_id);

-- The balance invariant. Deferred so the postings of one transaction can be
-- inserted in any order inside the transaction, and checked once at COMMIT.
-- An unbalanced transaction cannot reach disk.
CREATE FUNCTION assert_transaction_balances() RETURNS TRIGGER AS $$
DECLARE
    imbalance BIGINT;
BEGIN
    SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END), 0)
      INTO imbalance
      FROM postings
     WHERE transaction_id = COALESCE(NEW.transaction_id, OLD.transaction_id);

    IF imbalance <> 0 THEN
        RAISE EXCEPTION
            'ledger imbalance of % minor units on transaction %',
            imbalance, COALESCE(NEW.transaction_id, OLD.transaction_id)
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER postings_balance_check
    AFTER INSERT OR UPDATE OR DELETE ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_balances();

-- Append-only enforcement. The ledger is a log, not a table you edit.
CREATE FUNCTION reject_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'ledger rows are immutable: % on % is not allowed, post a reversing transaction instead',
        TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER postings_immutable
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER ledger_tx_immutable
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Balances are derived, never stored. A stored balance is a second source of
-- truth that drifts; this view cannot drift because there is nothing to drift
-- from. If it becomes too slow, the fix is a materialised rollup with a
-- reconciliation job, not a mutable balance column.
CREATE VIEW account_balances AS
SELECT a.id            AS account_id,
       a.tenant_id,
       a.code,
       a.type,
       a.currency,
       COALESCE(SUM(
           CASE
               WHEN a.type IN ('ASSET', 'EXPENSE') AND p.direction = 'DEBIT'  THEN  p.amount_minor
               WHEN a.type IN ('ASSET', 'EXPENSE') AND p.direction = 'CREDIT' THEN -p.amount_minor
               WHEN p.direction = 'CREDIT' THEN  p.amount_minor
               ELSE -p.amount_minor
           END), 0) AS balance_minor
  FROM accounts a
  LEFT JOIN postings p ON p.account_id = a.id
 GROUP BY a.id, a.tenant_id, a.code, a.type, a.currency;
