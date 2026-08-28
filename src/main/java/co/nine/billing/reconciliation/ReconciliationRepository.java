package co.nine.billing.reconciliation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The three drift queries. Each is a single set-based statement: the database
 * compares the two records in one pass and returns only the rows that differ.
 * Doing this row by row in Java would be O(n) round trips for what Postgres
 * answers with one join.
 */
@Repository
public class ReconciliationRepository {

    private final JdbcTemplate jdbc;

    public ReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Finding(String kind, UUID tenantId, String eventId, UUID transactionId,
                          Long expectedMinor, Long actualMinor, String detail) {}

    public long chargesCount() {
        return jdbc.queryForObject("SELECT count(*) FROM usage_charges", Long.class);
    }

    /** A charge whose receivable debit in the ledger is not what the charge says it charged. */
    public List<Finding> amountMismatches() {
        return jdbc.query("""
            SELECT c.tenant_id, c.event_id, c.transaction_id, c.charged_minor,
                   COALESCE(SUM(p.amount_minor), 0) AS ledger_minor
              FROM usage_charges c
              LEFT JOIN postings p
                     ON p.transaction_id = c.transaction_id
                    AND p.direction = 'DEBIT'
             GROUP BY c.tenant_id, c.event_id, c.transaction_id, c.charged_minor
            HAVING c.charged_minor <> COALESCE(SUM(p.amount_minor), 0)
               AND COUNT(p.id) > 0
            """,
            (rs, i) -> new Finding("AMOUNT_MISMATCH", rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, UUID.class), rs.getLong(4), rs.getLong(5),
                "charge says " + rs.getLong(4) + " minor, ledger debit says " + rs.getLong(5)));
    }

    /** A charge pointing at a transaction that has no postings at all. */
    public List<Finding> orphanCharges() {
        return jdbc.query("""
            SELECT c.tenant_id, c.event_id, c.transaction_id, c.charged_minor
              FROM usage_charges c
             WHERE NOT EXISTS (SELECT 1 FROM postings p WHERE p.transaction_id = c.transaction_id)
            """,
            (rs, i) -> new Finding("ORPHAN_CHARGE", rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, UUID.class), rs.getLong(4), 0L,
                "charge references a transaction with no postings"));
    }

    /** A transaction whose debits and credits do not sum to zero. The trigger should make this impossible. */
    /**
     * Transactions whose postings do not come to zero, per currency.
     *
     * <p>Grouped by currency because a transaction is balanced only when it
     * balances in every currency it touches. Summing across them lets a debit in
     * one currency cancel a credit in another, which is the blind spot the
     * balance trigger had and this query shared: the detector was written in the
     * same shape as the thing it was supposed to detect the failure of. A cross
     * currency transaction therefore reports once per offending currency, short
     * in one and long in the other, rather than collapsing into a single number
     * that reads as zero.
     */
    public List<Finding> unbalancedTransactions() {
        return jdbc.query("""
            SELECT t.tenant_id, t.id, p.currency,
                   SUM(CASE WHEN p.direction = 'DEBIT' THEN p.amount_minor ELSE -p.amount_minor END) AS imbalance
              FROM ledger_transactions t
              JOIN postings p ON p.transaction_id = t.id
             GROUP BY t.tenant_id, t.id, p.currency
            HAVING SUM(CASE WHEN p.direction = 'DEBIT' THEN p.amount_minor ELSE -p.amount_minor END) <> 0
             ORDER BY t.id, p.currency
            """,
            (rs, i) -> new Finding("UNBALANCED_TX", rs.getObject(1, UUID.class), null,
                rs.getObject(2, UUID.class), 0L, rs.getLong(4),
                "debits minus credits = " + rs.getLong(4) + " minor in " + rs.getString(3).trim()
                    + "; the balance trigger was bypassed"));
    }

    public long recordRun(Instant started, Instant finished, long checked,
                          long mismatches, long orphans, long unbalanced, List<Finding> findings) {
        Long runId = jdbc.queryForObject("""
            INSERT INTO reconciliation_runs
              (started_at, finished_at, charges_checked, amount_mismatches, orphan_charges, unbalanced_txs)
            VALUES (?, ?, ?, ?, ?, ?) RETURNING id
            """, Long.class,
            Timestamp.from(started), Timestamp.from(finished), checked, mismatches, orphans, unbalanced);

        for (Finding f : findings) {
            jdbc.update("""
                INSERT INTO reconciliation_findings
                  (run_id, kind, tenant_id, event_id, transaction_id, expected_minor, actual_minor, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, f.kind(), f.tenantId(), f.eventId(), f.transactionId(),
                f.expectedMinor(), f.actualMinor(), f.detail());
        }
        return runId;
    }

    public record RunSummary(long id, Instant startedAt, Instant finishedAt, long chargesChecked,
                             long amountMismatches, long orphanCharges, long unbalancedTxs, boolean clean) {}

    public List<RunSummary> recentRuns(int limit) {
        return jdbc.query("""
            SELECT id, started_at, finished_at, charges_checked, amount_mismatches, orphan_charges, unbalanced_txs, clean
              FROM reconciliation_runs ORDER BY started_at DESC LIMIT ?
            """,
            (rs, i) -> new RunSummary(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant(),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getBoolean(8)),
            limit);
    }

    public List<Finding> findingsOf(long runId) {
        return jdbc.query("""
            SELECT kind, tenant_id, event_id, transaction_id, expected_minor, actual_minor, detail
              FROM reconciliation_findings WHERE run_id = ?
            """,
            (rs, i) -> new Finding(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getLong(5), rs.getLong(6), rs.getString(7)),
            runId);
    }
}
