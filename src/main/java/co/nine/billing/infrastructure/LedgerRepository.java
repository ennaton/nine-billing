package co.nine.billing.infrastructure;

import co.nine.billing.domain.Direction;
import co.nine.billing.domain.DuplicateEntryException;
import co.nine.billing.domain.LedgerEntry;
import co.nine.billing.domain.Posting;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
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
     * Writes the transaction and its postings in one database transaction.
     * The deferred constraint trigger checks the balance at COMMIT, so an
     * unbalanced set is rejected atomically. A replayed idempotency key hits
     * the unique constraint and surfaces as DuplicateEntryException, which the
     * caller treats as "already done", not as failure.
     */
    @Transactional
    public UUID record(LedgerEntry entry) {
        UUID txId = UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO ledger_transactions
                    (id, tenant_id, idempotency_key, description, reverses_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                txId, entry.tenantId(), entry.idempotencyKey(), entry.description(),
                entry.reversesTransactionId().orElse(null), Timestamp.from(entry.occurredAt()));
        } catch (DuplicateKeyException e) {
            // Two different unique constraints can fire here and they mean
            // opposite things. The idempotency key colliding is a replay:
            // "already done", the caller should get the original id. The
            // reverses_id colliding is a real error: someone is trying to
            // reverse a transaction that has already been reversed.
            if (isViolationOf(e, "ledger_tx_tenant_idem_unique")) {
                throw new DuplicateEntryException(entry.idempotencyKey());
            }
            throw e;
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

    private static boolean isViolationOf(DuplicateKeyException e, String constraint) {
        Throwable c = e;
        while (c != null) {
            if (c.getMessage() != null && c.getMessage().contains(constraint)) return true;
            c = c.getCause();
        }
        return false;
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
