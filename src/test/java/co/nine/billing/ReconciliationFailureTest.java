package co.nine.billing;

import co.nine.billing.reconciliation.ReconciliationRepository;
import co.nine.billing.reconciliation.ReconciliationRepository.RunSummary;
import co.nine.billing.reconciliation.ReconciliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BI16.1. README says every run is recorded, clean or not. A run that cannot
 * complete is the case that sentence did not cover: it left the table
 * unchanged, so the newest row still said clean and the trail could not tell
 * "clean just now" from "clean an hour ago and broken since".
 */
@SpringBootTest
class ReconciliationFailureTest extends PostgresTestBase {

    @Autowired ReconciliationService reconciliation;
    @Autowired ReconciliationRepository repo;
    @Autowired JdbcTemplate jdbc;

    /** Outside the app's pool and outside RLS: the only way to take a grant away. */
    JdbcTemplate superuser() {
        return new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Test
    @DisplayName("a run that cannot complete is recorded, and it is not clean")
    void failedRunIsRecorded() {
        JdbcTemplate su = superuser();
        long before = su.queryForObject("SELECT count(*) FROM reconciliation_runs", Long.class);

        su.execute("REVOKE SELECT ON usage_charges FROM nine_operator");
        try {
            assertThatThrownBy(() -> reconciliation.run()).isInstanceOf(DataAccessException.class);

            long after = su.queryForObject("SELECT count(*) FROM reconciliation_runs", Long.class);
            assertThat(after).isEqualTo(before + 1);

            RunSummary newest = repo.recentRuns(1).get(0);
            assertThat(newest.clean()).isFalse();
            // Counters are null rather than zero: nothing was counted, and a
            // zero would read as "counted, found none".
            assertThat(newest.chargesChecked()).isNull();
            assertThat(newest.amountMismatches()).isNull();

            String state = su.queryForObject(
                "SELECT failure_code FROM reconciliation_runs ORDER BY id DESC LIMIT 1", String.class);
            assertThat(state).isEqualTo("42501");
        } finally {
            su.execute("GRANT SELECT ON usage_charges TO nine_operator");
        }
    }

    @Test
    @DisplayName("a row either counted or says why, and the database is what refuses the rest")
    void aRunCountsOrSaysWhy() {
        JdbcTemplate su = superuser();

        // Neither counts nor a reason: the generated expression is NULL and the
        // column refuses it, before the CHECK is reached.
        assertThatThrownBy(() -> su.update("""
            INSERT INTO reconciliation_runs (started_at, finished_at) VALUES (now(), now())
            """)).hasMessageContaining("null value in column \"clean\"");

        assertThatThrownBy(() -> su.update("""
            INSERT INTO reconciliation_runs
              (started_at, finished_at, charges_checked, amount_mismatches, orphan_charges, unbalanced_txs, failure_code)
            VALUES (now(), now(), 9, 0, 0, 0, '42501')
            """)).hasMessageContaining("reconciliation_runs_counted_or_said_why");

        // And the shape the service actually writes is accepted.
        su.update("""
            INSERT INTO reconciliation_runs (started_at, finished_at, failure_code)
            VALUES (now(), now(), '42501')
            """);
    }

    @Test
    @DisplayName("the reason is written where a tenant cannot read it")
    void reasonIsOperatorOnly() {
        JdbcTemplate su = superuser();
        su.execute("REVOKE SELECT ON usage_charges FROM nine_operator");
        try {
            assertThatThrownBy(() -> reconciliation.run()).isInstanceOf(DataAccessException.class);

            long runId = su.queryForObject("SELECT max(id) FROM reconciliation_runs", Long.class);
            String detail = su.queryForObject(
                "SELECT detail FROM reconciliation_findings WHERE run_id = ? AND kind = 'RUN_FAILED'",
                String.class, runId);
            assertThat(detail).contains("usage_charges");

            // As the tenant sees it: the flag is there and the findings table is
            // not readable at all. jdbc is the application pool, so this runs as
            // nine_app with no operator context, which is what a request is.
            assertThat(repo.recentRuns(1).get(0).clean()).isFalse();
            Long readable = jdbc.queryForObject("SELECT count(*) FROM reconciliation_findings", Long.class);
            assertThat(readable).isZero();
        } finally {
            su.execute("GRANT SELECT ON usage_charges TO nine_operator");
        }
    }
}
