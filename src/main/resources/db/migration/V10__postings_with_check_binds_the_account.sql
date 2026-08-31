-- BI12.3. A posting has two ends and the policy only checked one.
--
-- postings carries no tenant_id; it inherits one through its transaction, and
-- V4's WITH CHECK asks exactly that: does the transaction belong to the tenant
-- writing the row. Nothing asked who owns the account the posting lands on. The
-- composite foreign key binds the posting's currency to the account, never its
-- tenant, and neither trigger looks at ownership either.
--
-- So a tenant could write a balanced entry on a transaction it owned with one
-- leg landing on another tenant's account, and the database took it. Measured
-- before this file existed: the insert succeeds and the victim's account carries
-- the row.
--
-- Both ends are compared to current_tenant() rather than to each other, which is
-- the stronger statement: it refuses a cross tenant posting even in a session
-- that somehow held both tenants' rows in view.
--
-- USING is deliberately unchanged. It governs what a tenant may see, and the
-- hole was in what a tenant may write. Adding the account clause there would
-- also hide any row already written through the hole, and hiding evidence is the
-- opposite of what this ledger does with a bad entry.
--
-- Rows already written are not rewritten here. postings is immutable by trigger
-- and nine_app holds no UPDATE or DELETE grant, so the remedy for a bad entry is
-- a reversing transaction, which is a decision with an audit trail rather than a
-- migration that quietly edits history. Same reasoning as V7.
DROP POLICY tenant_isolation ON postings;

CREATE POLICY tenant_isolation ON postings
    USING      (is_operator() OR EXISTS (SELECT 1 FROM ledger_transactions t
                    WHERE t.id = postings.transaction_id AND t.tenant_id = current_tenant()))
    WITH CHECK (EXISTS (SELECT 1 FROM ledger_transactions t
                    WHERE t.id = postings.transaction_id AND t.tenant_id = current_tenant())
                AND EXISTS (SELECT 1 FROM accounts a
                    WHERE a.id = postings.account_id AND a.tenant_id = current_tenant()));
