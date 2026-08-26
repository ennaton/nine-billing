-- Correction to V4.
--
-- V4 gave both reconciliation tables `USING (true)`, justified by a comment
-- claiming they "hold no tenant data, only aggregate counts and references".
-- V3 says otherwise: reconciliation_findings carries tenant_id, event_id,
-- transaction_id and amounts. The comment was wrong, and the policy it
-- justified let any tenant with a valid API key read every other tenant's
-- findings through GET /v1/reconciliation/runs/{id}/findings.
--
-- The lesson is narrower than "review your policies": a `true` policy is only
-- ever as good as the sentence that explains it, and that sentence has to be
-- checked against the schema rather than against memory.

-- FORCE so the table owner is not exempt either. V4 set ENABLE but not FORCE
-- on these two, unlike every other table in the schema.
ALTER TABLE reconciliation_runs     FORCE ROW LEVEL SECURITY;
ALTER TABLE reconciliation_findings FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS operator_all ON reconciliation_runs;
DROP POLICY IF EXISTS operator_all ON reconciliation_findings;

-- runs holds counts and flags, no tenant identity, so any authenticated caller
-- may read it: it answers "was the last check clean", which is not a secret.
-- Writing is the job's business only.
CREATE POLICY runs_read_any ON reconciliation_runs
    FOR SELECT USING (true);
CREATE POLICY runs_write_operator ON reconciliation_runs
    FOR INSERT WITH CHECK (is_operator());

-- findings names tenants. Operator only, in both directions.
CREATE POLICY findings_operator_only ON reconciliation_findings
    FOR ALL USING (is_operator()) WITH CHECK (is_operator());
