package co.nine.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.nine.billing.application.LedgerService;
import co.nine.billing.domain.LedgerEntry;
import co.nine.billing.domain.Money;
import co.nine.billing.domain.Posting;
import co.nine.billing.domain.UnbalancedEntryException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import co.nine.billing.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The proof gate for nine-billing. Each test attacks one invariant and expects
 * the ledger to refuse. These run against a real Postgres (Testcontainers), not
 * an in-memory stand-in, because the invariants live in Postgres triggers and
 * constraints and nothing else would exercise them.
 */
@SpringBootTest
class LedgerInvariantsTest extends PostgresTestBase {



    @Autowired LedgerService ledger;
    @Autowired JdbcTemplate jdbc;

    final UUID tenant = UUID.randomUUID();
    UUID cash, revenue, usdCash;

    @BeforeEach
    void accounts() {
        // Under RLS every statement needs a tenant. The HTTP filter does this
        // per request; here, calling the service directly, the test does it.
        TenantContext.bind(tenant);
        cash = account("cash", "ASSET", "GBP");
        revenue = account("revenue", "REVENUE", "GBP");
        usdCash = account("usd_cash", "ASSET", "USD");
    }

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    // ---- 1. balanced entry commits ------------------------------------------------

    @Test @DisplayName("1. a balanced entry commits and moves both balances")
    void balancedEntryCommits() {
        ledger.post(entry("charge-1", debit(cash, 1250), credit(revenue, 1250)));
        assertThat(ledger.balanceMinor(cash)).isEqualTo(1250);
        assertThat(ledger.balanceMinor(revenue)).isEqualTo(1250);
    }

    // ---- 2. unbalanced entry is refused, twice --------------------------------------

    @Test @DisplayName("2a. Java refuses an unbalanced entry before any round trip")
    void unbalancedRefusedInJava() {
        assertThatThrownBy(() -> entry("bad", debit(cash, 1000), credit(revenue, 999)))
            .isInstanceOf(UnbalancedEntryException.class)
            .hasMessageContaining("imbalance=1");
    }

    @Test @DisplayName("2b. Postgres refuses an unbalanced entry at COMMIT even when Java is bypassed")
    void unbalancedRefusedByDatabase() {
        UUID tx = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.execute((java.sql.Connection c) -> {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("INSERT INTO ledger_transactions (id, tenant_id, idempotency_key, description, occurred_at) VALUES ('"
                    + tx + "','" + tenant + "','raw-bad','bypass', now())");
                st.execute("INSERT INTO postings (transaction_id, account_id, direction, amount_minor, currency) VALUES ('"
                    + tx + "','" + cash + "','DEBIT',1000,'GBP'),('" + tx + "','" + revenue + "','CREDIT',999,'GBP')");
            }
            c.commit();
            return null;
        })).hasMessageContaining("ledger imbalance");
        assertThat(count("SELECT count(*) FROM ledger_transactions WHERE id = ?", tx)).isZero();
    }

    @Test @DisplayName("2c. two currencies that net to zero are still unbalanced, and Postgres says so")
    void crossCurrencyNettingIsRefused() {
        // 100.00 GBP owed and 100.00 USD earned is not a balanced entry, it is two
        // half entries in different books. Each posting sits on an account of its
        // own currency, so postings_currency_matches_account is satisfied and does
        // not catch it: that constraint ties a posting to its account, not the two
        // sides of a transaction to each other. The balance is what has to notice,
        // and it only notices if it is computed per currency.
        UUID tx = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.execute((java.sql.Connection c) -> {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("INSERT INTO ledger_transactions (id, tenant_id, idempotency_key, description, occurred_at) VALUES ('"
                    + tx + "','" + tenant + "','raw-cross-currency','two books', now())");
                st.execute("INSERT INTO postings (transaction_id, account_id, direction, amount_minor, currency) VALUES ('"
                    + tx + "','" + cash + "','DEBIT',10000,'GBP'),('" + tx + "','" + usdCash + "','CREDIT',10000,'USD')");
            }
            c.commit();
            return null;
        })).hasMessageContaining("ledger imbalance");
        assertThat(count("SELECT count(*) FROM ledger_transactions WHERE id = ?", tx)).isZero();
    }

    // ---- 3. idempotency ------------------------------------------------------------

    @Test @DisplayName("3. replaying an idempotency key returns the same transaction and posts nothing new")
    void idempotencyKeyReplayIsNoOp() {
        UUID first = ledger.post(entry("charge-2", debit(cash, 500), credit(revenue, 500)));
        UUID again = ledger.post(entry("charge-2", debit(cash, 500), credit(revenue, 500)));
        assertThat(again).isEqualTo(first);
        assertThat(ledger.balanceMinor(cash)).isEqualTo(500);
        assertThat(count("SELECT count(*) FROM postings WHERE transaction_id = ?", first)).isEqualTo(2);
    }

    // ---- 4. currency mismatch ------------------------------------------------------

    @Test @DisplayName("4. a GBP posting cannot land on a USD account")
    void currencyMustMatchAccount() {
        assertThatThrownBy(() -> ledger.post(entry("charge-3", debit(usdCash, 500), credit(revenue, 500))))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("postings_currency_matches_account");
    }

    // ---- 5. non-positive amounts ---------------------------------------------------

    @Test @DisplayName("5. zero and negative amounts are refused at construction")
    void nonPositiveAmountsRefused() {
        assertThatThrownBy(() -> Posting.debit(cash, Money.of(0, "GBP")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Posting.debit(cash, Money.of(-100, "GBP")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 6 and 7. immutability -----------------------------------------------------

    @Test @DisplayName("6. UPDATE on a posting is rejected by the database, twice over")
    void updateRejected() {
        UUID tx = ledger.post(entry("charge-4", debit(cash, 100), credit(revenue, 100)));

        // Layer 1: the application role holds no UPDATE grant. Permission denied
        // before the trigger is even consulted.
        assertThatThrownBy(() -> jdbc.update("UPDATE postings SET amount_minor = 9999 WHERE transaction_id = ?", tx))
            .rootCause().hasMessageContaining("permission denied");

        // Layer 2: even the table owner, who has the grant, is stopped by the trigger.
        assertThatThrownBy(() -> superuser().update("UPDATE postings SET amount_minor = 9999 WHERE transaction_id = ?", tx))
            .rootCause().hasMessageContaining("immutable");

        assertThat(ledger.balanceMinor(cash)).isEqualTo(100);
    }

    @Test @DisplayName("7. DELETE on a transaction is rejected by the database, twice over")
    void deleteRejected() {
        UUID tx = ledger.post(entry("charge-5", debit(cash, 100), credit(revenue, 100)));

        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_transactions WHERE id = ?", tx))
            .rootCause().hasMessageContaining("permission denied");                       // no grant
        assertThatThrownBy(() -> superuser().update("DELETE FROM ledger_transactions WHERE id = ?", tx))
            .rootCause().hasMessageContaining("immutable");                               // trigger

        assertThat(count("SELECT count(*) FROM ledger_transactions WHERE id = ?", tx)).isEqualTo(1);
    }

    // ---- 8. reversal ---------------------------------------------------------------

    @Test @DisplayName("8. reversal is a new transaction that returns the balance to zero and keeps history")
    void reversalRestoresBalanceAndKeepsHistory() {
        UUID original = ledger.post(entry("charge-6", debit(cash, 1250), credit(revenue, 1250)));
        UUID reversal = ledger.reverse(tenant, original, "reverse-6", "refund");
        assertThat(reversal).isNotEqualTo(original);
        assertThat(ledger.balanceMinor(cash)).isZero();
        assertThat(ledger.balanceMinor(revenue)).isZero();
        assertThat(count("SELECT count(*) FROM ledger_transactions WHERE id IN (?, ?)", original, reversal)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT reverses_id FROM ledger_transactions WHERE id = ?", UUID.class, reversal))
            .isEqualTo(original);
    }

    @Test @DisplayName("8b. a transaction can be reversed only once")
    void reversalIsUnique() {
        UUID original = ledger.post(entry("charge-7", debit(cash, 300), credit(revenue, 300)));
        ledger.reverse(tenant, original, "reverse-7a", "refund");
        assertThatThrownBy(() -> ledger.reverse(tenant, original, "reverse-7b", "refund again"))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ledger_tx_reverses_unique");
    }

    // ---- 9. concurrency ------------------------------------------------------------

    @Test @DisplayName("9. 32 concurrent postings to the same account leave an exact balance")
    void concurrentPostingsStayConsistent() throws Exception {
        int workers = 32;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < workers; i++) {
            final int n = i;
            pool.submit(() -> {
                start.await();
                TenantContext.bind(tenant);   // ThreadLocal: each worker binds its own
                try {
                    ledger.post(entry("concurrent-" + n, debit(cash, 10), credit(revenue, 10)));
                } finally {
                    TenantContext.clear();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(ledger.balanceMinor(cash)).isEqualTo(320);
        assertThat(ledger.balanceMinor(revenue)).isEqualTo(320);
    }

    // ---- helpers -------------------------------------------------------------------

    /** The table owner, outside the app's pool. The only principal that can reach the triggers. */
    JdbcTemplate superuser() {
        return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    UUID account(String code, String type, String ccy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, tenant_id, code, type, currency) VALUES (?, ?, ?, ?, ?)",
            id, tenant, code + "-" + id.toString().substring(0, 8), type, ccy);
        return id;
    }

    LedgerEntry entry(String key, Posting... postings) {
        return new LedgerEntry(tenant, key, "test " + key, Instant.now(), List.of(postings), Optional.empty());
    }

    static Posting debit(UUID acct, long minor) { return Posting.debit(acct, Money.of(minor, "GBP")); }
    static Posting credit(UUID acct, long minor) { return Posting.credit(acct, Money.of(minor, "GBP")); }

    long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }
}
