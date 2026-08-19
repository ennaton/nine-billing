package co.nine.billing;

import co.nine.billing.metering.MeteringService;
import co.nine.billing.metering.UsageEvent;
import co.nine.billing.reconciliation.ReconciliationService;
import co.nine.billing.reconciliation.ReconciliationService.Report;
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

    @Test @Order(1)
    @DisplayName("a healthy ledger reconciles clean, and the clean run is recorded")
    void cleanLedgerIsClean() {
        chargedTx = metering.charge(new UsageEvent("evt-r1", TENANT, "seats", 2, Instant.now())).transactionId();
        metering.charge(new UsageEvent("evt-r2", TENANT, "agent_seconds", 30, Instant.now()));

        Report r = reconciliation.run();

        assertThat(r.clean()).isTrue();
        assertThat(r.chargesChecked()).isEqualTo(2);
        assertThat(r.findings()).isEmpty();
        cleanRunId = r.runId();
        Boolean stored = jdbc.queryForObject("SELECT clean FROM reconciliation_runs WHERE id = ?", Boolean.class, r.runId());
        assertThat(stored).isTrue();
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

    @Test @Order(3)
    @DisplayName("findings are persisted per run: the clean run has none, the dirty run has its drift")
    void findingsArePersisted() {
        // The scheduler also runs once at startup, so count by run id, not globally.
        Long cleanFindings = jdbc.queryForObject(
            "SELECT count(*) FROM reconciliation_findings WHERE run_id = ?", Long.class, cleanRunId);
        Long dirtyFindings = jdbc.queryForObject(
            "SELECT count(*) FROM reconciliation_findings WHERE run_id = ?", Long.class, dirtyRunId);
        Boolean dirtyFlag = jdbc.queryForObject(
            "SELECT clean FROM reconciliation_runs WHERE id = ?", Boolean.class, dirtyRunId);

        assertThat(cleanFindings).isZero();
        assertThat(dirtyFindings).isGreaterThanOrEqualTo(2);
        assertThat(dirtyFlag).isFalse();
    }
}
