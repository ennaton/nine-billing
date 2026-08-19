-- Reconciliation: two independent records of the same money, compared.
--
-- usage_charges says what metering believes it charged. postings says what
-- the ledger actually holds. Same transaction, so in theory they cannot
-- disagree. In practice a manual SQL fix, a bypassed trigger, a price plan
-- edit, or a bug in this very service can split them, and the only way to
-- know is to compare. This table is the record of every comparison.

CREATE TABLE reconciliation_runs (
    id                  BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at          TIMESTAMPTZ NOT NULL,
    finished_at         TIMESTAMPTZ NOT NULL,
    charges_checked     BIGINT      NOT NULL,
    -- Each kind of drift counted separately so the report says what kind of
    -- wrong, not just "wrong".
    amount_mismatches   BIGINT      NOT NULL,   -- charge says X, ledger debit says Y
    orphan_charges      BIGINT      NOT NULL,   -- charge points at a tx with no postings
    unbalanced_txs      BIGINT      NOT NULL,   -- a transaction whose debits != credits
    clean               BOOLEAN     NOT NULL GENERATED ALWAYS AS
                        (amount_mismatches = 0 AND orphan_charges = 0 AND unbalanced_txs = 0) STORED
);

CREATE INDEX reconciliation_runs_started_idx ON reconciliation_runs (started_at DESC);

-- The detail rows: one per drift found, so an operator can go straight to it.
CREATE TABLE reconciliation_findings (
    run_id          BIGINT  NOT NULL REFERENCES reconciliation_runs (id),
    kind            TEXT    NOT NULL CHECK (kind IN ('AMOUNT_MISMATCH', 'ORPHAN_CHARGE', 'UNBALANCED_TX')),
    tenant_id       UUID,
    event_id        TEXT,
    transaction_id  UUID,
    expected_minor  BIGINT,
    actual_minor    BIGINT,
    detail          TEXT    NOT NULL
);

CREATE INDEX reconciliation_findings_run_idx ON reconciliation_findings (run_id);
