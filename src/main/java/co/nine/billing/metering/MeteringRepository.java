package co.nine.billing.metering;

import co.nine.billing.domain.AccountType;
import co.nine.billing.domain.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MeteringRepository {

    private final JdbcTemplate jdbc;

    public MeteringRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PricePlan> pricePlan(String metric) {
        return jdbc.query(
            "SELECT metric, unit_price_minor, currency, description FROM price_plans WHERE metric = ?",
            rs -> rs.next()
                ? Optional.of(new PricePlan(rs.getString(1), rs.getLong(2), rs.getString(3).trim(), rs.getString(4)))
                : Optional.empty(),
            metric);
    }

    /**
     * The tenant's account for a role, created on first use. INSERT ... ON
     * CONFLICT keeps this safe under concurrent first events: two threads race,
     * one inserts, the other reads the same id. No application lock.
     */
    public UUID ensureAccount(UUID tenantId, String code, AccountType type, String currency) {
        // DO NOTHING, not DO UPDATE: accounts never change once created, and
        // nine_app holds no UPDATE grant on the table, by design. The follow-up
        // SELECT resolves the race: whoever lost the insert reads the winner's id.
        jdbc.update("""
            INSERT INTO accounts (id, tenant_id, code, type, currency)
            VALUES (gen_random_uuid(), ?, ?, ?, ?)
            ON CONFLICT (tenant_id, code) DO NOTHING
            """, tenantId, code, type.name(), currency);
        return jdbc.queryForObject(
            "SELECT id FROM accounts WHERE tenant_id = ? AND code = ?", UUID.class, tenantId, code);
    }

    /** What an event was actually charged, as opposed to what it would cost now. */
    public record RecordedCharge(UUID transactionId, Money amount) {}

    /**
     * The charge already on record for this event, if there is one.
     *
     * <p>Returns the stored amount and currency, not just the transaction id,
     * because a replay is answered from what happened rather than from what the
     * request in hand would cost. Those are the same number only until a price
     * changes or a client retries with a different quantity.
     */
    public Optional<RecordedCharge> chargeFor(UUID tenantId, String eventId) {
        return jdbc.query("""
            SELECT transaction_id, charged_minor, currency
              FROM usage_charges
             WHERE tenant_id = ? AND event_id = ?
            """,
            rs -> rs.next()
                ? Optional.of(new RecordedCharge(
                    rs.getObject(1, UUID.class),
                    Money.of(rs.getLong(2), rs.getString(3).trim())))
                : Optional.empty(),
            tenantId, eventId);
    }

    /**
     * Records the charge, and reports whether this call is the one that made it.
     *
     * <p>Returns false when the row was already there, which is a replay that got
     * past the check in front of it: two callers with the same event both miss
     * the lookup, and only one of them writes. The conflict is suppressed rather
     * than raised for the same reason as in the ledger, this runs inside the
     * caller's transaction and a raised violation would take that transaction
     * down with it.
     */
    public boolean recordCharge(UsageEvent e, long chargedMinor, String currency, UUID txId) {
        return jdbc.update("""
            INSERT INTO usage_charges
              (event_id, tenant_id, metric, quantity, charged_minor, currency, transaction_id, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, event_id) DO NOTHING
            """,
            e.eventId(), e.tenantId(), e.metric(), e.quantity(), chargedMinor, currency, txId,
            java.sql.Timestamp.from(e.occurredAt())) == 1;
    }

    public record LedgerLine(UUID transactionId, String description, Instant occurredAt,
                             String account, String direction, long amountMinor, String currency) {}

    public java.util.List<LedgerLine> recentLines(UUID tenantId, int limit) {
        return jdbc.query("""
            SELECT t.id, t.description, t.occurred_at, a.code, p.direction, p.amount_minor, p.currency
              FROM ledger_transactions t
              JOIN postings p ON p.transaction_id = t.id
              JOIN accounts a ON a.id = p.account_id
             WHERE t.tenant_id = ?
             ORDER BY t.occurred_at DESC, t.recorded_at DESC, p.id
             LIMIT ?
            """,
            (rs, i) -> new LedgerLine(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5),
                rs.getLong(6), rs.getString(7).trim()),
            tenantId, limit);
    }
}
