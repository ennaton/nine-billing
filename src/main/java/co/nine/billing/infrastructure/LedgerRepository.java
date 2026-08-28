package co.nine.billing.infrastructure;

import co.nine.billing.domain.Direction;
import co.nine.billing.domain.LedgerEntry;
import co.nine.billing.domain.Posting;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plain JDBC on purpose. The ledger's guarantees live in the schema (deferred
 * balance trigger, unique idempotency key, immutability triggers, composite
 * currency FK), and JDBC keeps that visible: every statement here maps to a
 * row and a constraint you can read in V1__ledger.sql. An ORM would hide the
 * exact moment the database says no.
 */
@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes the transaction and its postings in one database transaction, and
     * answers a replay with the transaction that already holds the key.
     *
     * <p>The deferred constraint trigger checks the balance at COMMIT, so an
     * unbalanced set is rejected atomically.
     *
     * <p>The replay is resolved with {@code ON CONFLICT DO NOTHING} rather than
     * by catching the violation, and the difference is not style. This method
     * joins its caller's transaction, so a violation raised here aborts that
     * whole transaction in Postgres and every later statement in it fails with
     * 25P02, including the one that would read the original id. Suppressing the
     * exception is not enough either: throwing anything out of a participating
     * transactional method makes Spring mark the shared transaction
     * rollback-only, and the caller's commit then fails with
     * UnexpectedRollbackException instead. Both were measured. The constraint
     * still decides, it just no longer has to raise to do it.
     *
     * <p>Only the idempotency key is suppressed. A reverses_id collision is a
     * real conflict, someone reversing a transaction that is already reversed,
     * and it still surfaces as an exception.
     */
    @Transactional
    public UUID record(LedgerEntry entry) {
        UUID txId = UUID.randomUUID();
        int inserted = jdbc.update("""
            INSERT INTO ledger_transactions
                (id, tenant_id, idempotency_key, description, reverses_id, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            """,
            txId, entry.tenantId(), entry.idempotencyKey(), entry.description(),
            entry.reversesTransactionId().orElse(null), Timestamp.from(entry.occurredAt()));

        if (inserted == 0) {
            // The key is taken, so this is a replay and the caller is owed the
            // transaction that holds it. No postings are written: they belong to
            // the transaction that won.
            return transactionIdFor(entry.tenantId(), entry.idempotencyKey());
        }

        List<Object[]> rows = entry.postings().stream()
            .map(p -> new Object[] {
                txId, p.accountId(), p.direction().name(),
                p.amount().minor(), p.amount().currency().getCurrencyCode()})
            .toList();
        jdbc.batchUpdate("""
            INSERT INTO postings (transaction_id, account_id, direction, amount_minor, currency)
            VALUES (?, ?, ?, ?, ?)
            """, rows);
        return txId;
    }

    public long balanceMinor(UUID accountId) {
        Long v = jdbc.queryForObject(
            "SELECT balance_minor FROM account_balances WHERE account_id = ?", Long.class, accountId);
        return v == null ? 0L : v;
    }

    /** Postings of one transaction, used to build its reversal. */
    public List<Posting> postingsOf(UUID transactionId) {
        return jdbc.query("""
            SELECT account_id, direction, amount_minor, currency
              FROM postings WHERE transaction_id = ? ORDER BY id
            """,
            (rs, i) -> new Posting(
                rs.getObject("account_id", UUID.class),
                Direction.valueOf(rs.getString("direction")),
                co.nine.billing.domain.Money.of(rs.getLong("amount_minor"), rs.getString("currency"))),
            transactionId);
    }

    public UUID transactionIdFor(UUID tenantId, String idempotencyKey) {
        return jdbc.queryForObject(
            "SELECT id FROM ledger_transactions WHERE tenant_id = ? AND idempotency_key = ?",
            UUID.class, tenantId, idempotencyKey);
    }
}
