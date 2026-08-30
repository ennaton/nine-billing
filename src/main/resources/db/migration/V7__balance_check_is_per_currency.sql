-- The balance check summed debits minus credits across the whole transaction with
-- no currency dimension, so two half entries in different books netted to zero and
-- committed. Measured before this migration: a DEBIT of 10000 GBP and a CREDIT of
-- 10000 USD, each on an account of its own currency, commits and both rows reach
-- disk. 100.00 GBP is not 100.00 USD, and the ledger said it was.
--
-- postings_currency_matches_account does not catch this and was never going to. It
-- ties a posting to its account, not the two sides of a transaction to each other,
-- so both postings satisfy it. The balance is what has to notice, and it only
-- notices if it is computed per currency.
--
-- A transaction is balanced when it balances in every currency it touches, so the
-- sum is grouped and the first currency that does not come to zero is the error.
CREATE OR REPLACE FUNCTION assert_transaction_balances() RETURNS TRIGGER AS $$
DECLARE
    offending RECORD;
BEGIN
    SELECT p.currency AS currency,
           SUM(CASE WHEN p.direction = 'DEBIT' THEN p.amount_minor ELSE -p.amount_minor END) AS imbalance
      INTO offending
      FROM postings p
     WHERE p.transaction_id = COALESCE(NEW.transaction_id, OLD.transaction_id)
     GROUP BY p.currency
    HAVING SUM(CASE WHEN p.direction = 'DEBIT' THEN p.amount_minor ELSE -p.amount_minor END) <> 0
     ORDER BY p.currency
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'ledger imbalance of % minor units in % on transaction %',
            offending.imbalance, offending.currency,
            COALESCE(NEW.transaction_id, OLD.transaction_id)
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Deliberately not re-validating what is already stored. CREATE OR REPLACE changes
-- the rule for every write from here on and leaves history alone, which is the same
-- promise the rest of this schema makes: nothing is rewritten. Any cross currency
-- transaction that committed before this migration stays on disk and is now
-- reported by reconciliation, which is where a past mistake belongs.
