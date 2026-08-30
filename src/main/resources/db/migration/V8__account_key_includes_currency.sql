-- BI13.3. The account key was (tenant_id, code), so the first currency a tenant
-- was ever charged in became the only currency it could ever be charged in. A
-- second currency collided with the first, the upsert did nothing, and the caller
-- was handed an account in the wrong book. nine_app holds no UPDATE or DELETE
-- grant on accounts, so no code path could undo it.
--
-- Widening the key rather than replacing it. Every row that was unique on
-- (tenant_id, code) is still unique on (tenant_id, code, currency), because
-- adding a column to a unique key can only ever separate rows, never merge them.
-- So there is no data to migrate and no window where the table is unconstrained
-- inside this transaction.
--
-- accounts_id_currency_unique is untouched. It is the target of the composite
-- foreign key from postings and it is what keeps a GBP posting off a USD account.
-- That guarantee is per account and is unaffected by how accounts are keyed.
ALTER TABLE accounts DROP CONSTRAINT accounts_tenant_code_unique;
ALTER TABLE accounts ADD CONSTRAINT accounts_tenant_code_currency_unique
    UNIQUE (tenant_id, code, currency);
