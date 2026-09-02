-- BI16.1. README says every run is recorded, clean or not. A run that could not
-- complete was not recorded at all, so the newest row still read clean and the
-- trail could not tell "clean just now" from "clean an hour ago, broken since".
--
-- Counters become nullable because a failed run counted nothing, and a zero
-- would claim it counted and found none. The CHECK is what makes that exact:
-- a row carries either a reason or all four counts, never both and never
-- neither. clean stays NOT NULL, so the database refuses the half record rather
-- than the service remembering to.
--
-- Postgres 16 cannot alter a generated expression, so the column is dropped and
-- re-added, which rewrites the table under ACCESS EXCLUSIVE. The row count is
-- per instance, so it is not quoted here: the lock is held for as long as that
-- instance's table takes to rewrite.
ALTER TABLE reconciliation_runs DROP COLUMN clean;

-- SQLState when the database refused, the exception's simple name otherwise.
-- Both are closed vocabularies. The readable message is not here: every tenant
-- reads this table under runs_read_any, and a driver message carries the
-- statement and sometimes a value.
ALTER TABLE reconciliation_runs ADD COLUMN failure_code TEXT;

ALTER TABLE reconciliation_runs ALTER COLUMN charges_checked    DROP NOT NULL;
ALTER TABLE reconciliation_runs ALTER COLUMN amount_mismatches  DROP NOT NULL;
ALTER TABLE reconciliation_runs ALTER COLUMN orphan_charges     DROP NOT NULL;
ALTER TABLE reconciliation_runs ALTER COLUMN unbalanced_txs     DROP NOT NULL;

ALTER TABLE reconciliation_runs ADD COLUMN clean BOOLEAN NOT NULL GENERATED ALWAYS AS
    (failure_code IS NULL AND amount_mismatches = 0 AND orphan_charges = 0 AND unbalanced_txs = 0) STORED;

ALTER TABLE reconciliation_runs ADD CONSTRAINT reconciliation_runs_counted_or_said_why CHECK (
    (failure_code IS NULL) = (charges_checked IS NOT NULL AND amount_mismatches IS NOT NULL
                              AND orphan_charges IS NOT NULL AND unbalanced_txs IS NOT NULL));

ALTER TABLE reconciliation_findings DROP CONSTRAINT reconciliation_findings_kind_check;
ALTER TABLE reconciliation_findings ADD CONSTRAINT reconciliation_findings_kind_check
    CHECK (kind IN ('AMOUNT_MISMATCH', 'ORPHAN_CHARGE', 'UNBALANCED_TX', 'RUN_FAILED'));
