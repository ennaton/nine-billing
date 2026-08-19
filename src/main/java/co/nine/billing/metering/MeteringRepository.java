package co.nine.billing.metering;

import co.nine.billing.domain.AccountType;
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
        return jdbc.queryForObject("""
            INSERT INTO accounts (id, tenant_id, code, type, currency)
            VALUES (gen_random_uuid(), ?, ?, ?, ?)
            ON CONFLICT (tenant_id, code) DO UPDATE SET code = EXCLUDED.code
            RETURNING id
            """, UUID.class, tenantId, code, type.name(), currency);
    }

    public Optional<UUID> chargeFor(UUID tenantId, String eventId) {
        return jdbc.query(
            "SELECT transaction_id FROM usage_charges WHERE tenant_id = ? AND event_id = ?",
            rs -> rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty(),
            tenantId, eventId);
    }

    public void recordCharge(UsageEvent e, long chargedMinor, String currency, UUID txId) {
        jdbc.update("""
            INSERT INTO usage_charges
              (event_id, tenant_id, metric, quantity, charged_minor, currency, transaction_id, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            e.eventId(), e.tenantId(), e.metric(), e.quantity(), chargedMinor, currency, txId,
            java.sql.Timestamp.from(e.occurredAt()));
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
