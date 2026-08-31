-- The balance check could pass by seeing nothing.
--
-- assert_transaction_balances is SECURITY INVOKER, so its own SELECT over
-- postings is subject to the same row-level security as whoever caused it to
-- fire. With no tenant bound the policy hides every row, the aggregate runs over
-- an empty set, HAVING finds nothing, FOUND is false, and the function returns
-- without raising. Fail closed on the read becomes fail open on the invariant.
--
-- Measured rather than reasoned about, by taking invariant 2b's own test and
-- adding one statement between the inserts and the commit:
--
--     SELECT set_config('app.tenant_id', '', false)
--
-- A transaction of 1000 DEBIT and 999 CREDIT then committed, and the imbalance
-- of one minor unit was on disk afterwards.
--
-- Not reachable through the service today: TenantAwareDataSource writes the GUC
-- at connection checkout and Spring holds that same connection through COMMIT,
-- so nothing clears it midway. It is one refactor away, and CLAUDE.md states
-- invariant 2 without the condition, which is the part that has to stop being
-- true by luck.
--
-- The fix asks a question before the arithmetic: can this function see the rows
-- it was fired for? It fired because a posting exists on this transaction, so
-- seeing none means its view is wrong rather than the entry being empty. A check
-- that cannot see its subject must refuse, never pass.
--
-- internal_error rather than check_violation on purpose. This is not a rule the
-- caller broke, and ConstraintRules deliberately maps nothing to it, so the
-- caller is told the write was refused rather than handed a friendly message
-- that would hide a defect in this service. That is what ConstraintRules'
-- own contract already says should happen to an unrecognised integrity failure.
--
-- DELETE is the one path where zero visible rows could be legitimate, and it
-- cannot arrive here: postings_immutable rejects DELETE before this trigger is
-- deferred to COMMIT. If that trigger is ever disabled, emptying a transaction
-- is refused by this guard instead, which is the same answer.
CREATE OR REPLACE FUNCTION assert_transaction_balances() RETURNS TRIGGER AS $$
DECLARE
    txid UUID := COALESCE(NEW.transaction_id, OLD.transaction_id);
    offending RECORD;
BEGIN
    -- EXISTS rather than count(*). This trigger is FOR EACH ROW, so it runs once
    -- per posting, and the question is only whether the function is blind. EXISTS
    -- stops at the first row; counting would walk them all for an answer nobody
    -- reads.
    IF NOT EXISTS (SELECT 1 FROM postings p WHERE p.transaction_id = txid) THEN
        RAISE EXCEPTION
            'balance check cannot see transaction %, refusing rather than passing', txid
            USING ERRCODE = 'internal_error';
    END IF;

    SELECT p.currency AS currency,
           SUM(CASE WHEN p.direction = 'DEBIT' THEN p.amount_minor ELSE -p.amount_minor END) AS imbalance
      INTO offending
      FROM postings p
     WHERE p.transaction_id = txid
     GROUP BY p.currency
    HAVING SUM(CASE WHEN p.direction = 'DEBIT' THEN p.amount_minor ELSE -p.amount_minor END) <> 0
     ORDER BY p.currency
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'ledger imbalance of % minor units in % on transaction %',
            offending.imbalance, offending.currency, txid
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
