package co.nine.billing;

import co.nine.billing.metering.MeteringService;
import co.nine.billing.metering.UsageEvent;
import co.nine.billing.reconciliation.ReconciliationService;
import co.nine.billing.reconciliation.ReconciliationService.Report;
import co.nine.billing.auth.OperatorContext;
import co.nine.billing.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciliation is only worth having if it catches drift. So these tests
 * create drift on purpose, by the only route that exists: a superuser
 * switching the immutability trigger off and editing rows underneath the
 * service. If that does not show up in the report, the job is decoration.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconciliationTest extends PostgresTestBase {



    @Autowired MeteringService metering;
    @Autowired ReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;

    static final UUID TENANT = UUID.randomUUID();
    static UUID chargedTx;
    static long cleanRunId, dirtyRunId;

    @BeforeEach void bind()   { TenantContext.bind(TENANT); }
    @AfterEach  void unbind() { TenantContext.clear(); }

    /** Superuser connection, outside the app's pool and outside RLS: the only way to tamper. */
    JdbcTemplate superuser() {
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        return new JdbcTemplate(ds);
    }

    /**
     * Reconciliation is global: it compares every tenant's charges against
     * every tenant's postings, because drift does not respect tenancy. So a
     * test cannot assert "the run was clean" while sharing a database with
     * other tests that tamper on purpose. It asserts the narrower and truer
     * thing: nothing is wrong with THIS tenant, and the run was recorded.
     */
    @Test @Order(1)
    @DisplayName("a healthy ledger produces no findings against its own tenant, and the run is recorded")
    void cleanLedgerIsClean() {
        chargedTx = metering.charge(new UsageEvent("evt-r1", TENANT, "seats", 2, Instant.now())).transactionId();
        metering.charge(new UsageEvent("evt-r2", TENANT, "agent_seconds", 30, Instant.now()));

        Report r = reconciliation.run();
        cleanRunId = r.runId();

        assertThat(r.chargesChecked())
            .as("the run is global, so it sees at least this tenant's two charges")
            .isGreaterThanOrEqualTo(2);
        assertThat(String.valueOf(r.findings()))
            .as("no finding may name this tenant, whatever other tenants are doing")
            .doesNotContain(TENANT.toString());

        Long recorded = jdbc.queryForObject(
            "SELECT count(*) FROM reconciliation_runs WHERE id = ?", Long.class, r.runId());
        assertThat(recorded).as("every run is recorded, clean or not").isEqualTo(1);
    }

    @Test @Order(2)
    @DisplayName("an amount edited underneath the service shows up as AMOUNT_MISMATCH")
    void tamperedAmountIsCaught() {
        // The only way to get here: bypass immutability AND row-level security
        // as a superuser. nine_app cannot do either; that is the point.
        JdbcTemplate su = superuser();
        su.execute("ALTER TABLE postings DISABLE TRIGGER postings_immutable");
        su.execute("ALTER TABLE postings DISABLE TRIGGER postings_balance_check");
        su.update("UPDATE postings SET amount_minor = amount_minor + 1 WHERE transaction_id = ? AND direction = 'DEBIT'", chargedTx);
        su.execute("ALTER TABLE postings ENABLE TRIGGER postings_immutable");
        su.execute("ALTER TABLE postings ENABLE TRIGGER postings_balance_check");

        Report r = reconciliation.run();

        dirtyRunId = r.runId();
        assertThat(r.clean()).isFalse();
        assertThat(r.findings()).extracting("kind").contains("AMOUNT_MISMATCH", "UNBALANCED_TX");
        var mismatch = r.findings().stream().filter(f -> f.kind().equals("AMOUNT_MISMATCH")).findFirst().orElseThrow();
        assertThat(mismatch.transactionId()).isEqualTo(chargedTx);
        assertThat(mismatch.expectedMinor()).isEqualTo(1800);  // 2 seats x 900
        assertThat(mismatch.actualMinor()).isEqualTo(1801);
    }

    /**
     * Findings are readable only in operator context. A tenant bound connection
     * sees zero rows, which is the policy working rather than data missing, so
     * the test asserts both halves.
     */
    @Test @Order(3)
    @DisplayName("findings are operator readable only, and the dirty run recorded its drift")
    void findingsArePersisted() {
        Long asTenant = jdbc.queryForObject(
            "SELECT count(*) FROM reconciliation_findings WHERE run_id = ?", Long.class, dirtyRunId);
        assertThat(asTenant)
            .as("a tenant bound connection must not read findings at all")
            .isZero();

        Long asOperator = OperatorContext.run(() -> jdbc.queryForObject(
            "SELECT count(*) FROM reconciliation_findings WHERE run_id = ?", Long.class, dirtyRunId));
        assertThat(asOperator)
            .as("the same rows are there for an operator")
            .isGreaterThanOrEqualTo(2);

        Boolean dirtyFlag = OperatorContext.run(() -> jdbc.queryForObject(
            "SELECT clean FROM reconciliation_runs WHERE id = ?", Boolean.class, dirtyRunId));
        assertThat(dirtyFlag).isFalse();
    }

    /**
     * The detector shares its shape with the trigger that is supposed to make
     * this impossible: debits minus credits, with no currency dimension. So the
     * same two half entries the trigger has to refuse are the ones reconciliation
     * has to find if they ever reach disk, and the only way they reach disk is
     * the trigger being off.
     */
    @Test @Order(4)
    @DisplayName("a transaction that nets to zero across two currencies is reported, not passed over")
    void crossCurrencyImbalanceIsReported() {
        JdbcTemplate su = superuser();
        UUID other = UUID.randomUUID();
        UUID gbpAccount = UUID.randomUUID();
        UUID usdAccount = UUID.randomUUID();
        UUID tx = UUID.randomUUID();

        // Its own tenant, so the assertions the earlier tests make about theirs
        // stay true however the classes end up ordered.
        su.update("INSERT INTO accounts (id, tenant_id, code, type, currency) VALUES (?,?,?,?,?)",
            gbpAccount, other, "cash", "ASSET", "GBP");
        su.update("INSERT INTO accounts (id, tenant_id, code, type, currency) VALUES (?,?,?,?,?)",
            usdAccount, other, "usd_cash", "ASSET", "USD");
        su.update("INSERT INTO ledger_transactions (id, tenant_id, idempotency_key, description, occurred_at)"
            + " VALUES (?,?,?,?, now())", tx, other, "cross-currency-probe", "two books");

        su.execute("ALTER TABLE postings DISABLE TRIGGER postings_balance_check");
        try {
            su.update("INSERT INTO postings (transaction_id, account_id, direction, amount_minor, currency)"
                + " VALUES (?,?,'DEBIT',10000,'GBP'), (?,?,'CREDIT',10000,'USD')",
                tx, gbpAccount, tx, usdAccount);
        } finally {
            // finally rather than the happy path: a failure between the disable
            // and the enable would leave the balance trigger off for every test
            // that runs after this one, and they would fail for reasons that have
            // nothing to do with the code they test.
            su.execute("ALTER TABLE postings ENABLE TRIGGER postings_balance_check");
        }

        Report r = reconciliation.run();

        assertThat(r.findings())
            .as("the planted transaction must produce findings, or this test is vacuous")
            .isNotEmpty();

        // Two findings, not one. The transaction is short 100.00 in GBP and long
        // 100.00 in USD, and collapsing that into a single number is how it went
        // unnoticed in the first place.
        assertThat(r.findings())
            .filteredOn(f -> "UNBALANCED_TX".equals(f.kind()) && tx.equals(f.transactionId()))
            .as("one finding per currency that does not balance")
            .hasSize(2)
            .extracting("detail", String.class)
            .anySatisfy(detail -> assertThat(detail).contains("GBP"))
            .anySatisfy(detail -> assertThat(detail).contains("USD"));
    }
}
