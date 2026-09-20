package co.nine.billing;

import co.nine.billing.reconciliation.ReconciliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * BI9.1 and BI16.1 together, because both answer one question: which indicator
 * belongs to which health group. Boot puts only livenessState in liveness and
 * only readinessState in readiness, so neither the database nor a reconciliation
 * indicator reaches a probe unless the group says so. A new indicator lands in
 * the root group and nowhere else, so a probe that ought to have noticed it
 * stays green until the group is written down.
 *
 * <p>Its own container: the first case stops the database, and stopping the one
 * the rest of the suite shares would take the suite with it. That is also why
 * the methods are ordered, the stop is not undone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.datasource.hikari.connection-timeout=1000",
                  "spring.datasource.hikari.initialization-fail-timeout=-1"})
@AutoConfigureTestRestTemplate
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HealthProbesTest {

    static final PostgreSQLContainer OWN = new PostgreSQLContainer("postgres:16-alpine")
        .withCopyFileToContainer(MountableFile.forClasspathResource("db/bootstrap.sql"),
                                 "/docker-entrypoint-initdb.d/20-bootstrap.sql");

    static {
        OWN.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", OWN::getJdbcUrl);
        r.add("spring.datasource.username", () -> "nine_app");
        r.add("spring.flyway.url", OWN::getJdbcUrl);
        r.add("nine.billing.reconcile.interval", () -> "PT24H");
        r.add("nine.billing.reconcile.timeout", () -> "PT3S");
        r.add("nine.billing.bootstrap-secret", () -> "test-bootstrap-secret");
    }

    @Autowired TestRestTemplate http;
    @Autowired ReconciliationService reconciliation;

    /** Outside the app's pool and outside RLS: the only way to take a grant away. */
    JdbcTemplate superuser() {
        return new JdbcTemplate(new DriverManagerDataSource(
            OWN.getJdbcUrl(), OWN.getUsername(), OWN.getPassword()));
    }

    String status(String path) {
        return http.getForEntity(path, String.class).getBody();
    }

    @Test
    @Order(1)
    @DisplayName("a broken reconciliation turns health red and leaves liveness green")
    void brokenReconciliationIsRedButNotFatal() {
        assertThat(status("/actuator/health")).contains("\"status\":\"UP\"");

        superuser().execute("REVOKE SELECT ON usage_charges FROM nine_operator");
        try {
            try {
                reconciliation.run();
                fail("the run was expected to fail with the grant taken away");
            } catch (RuntimeException expected) {
                // The recorded failure is what health is supposed to see.
            }

            assertThat(status("/actuator/health")).contains("\"status\":\"DOWN\"");
            // A reconciliation that cannot run is not a reason to restart the
            // process, so this must stay green while the one above is red.
            assertThat(status("/actuator/health/liveness")).contains("\"status\":\"UP\"");
        } finally {
            superuser().execute("GRANT SELECT ON usage_charges TO nine_operator");
        }
    }

    @Test
    @Order(2)
    @DisplayName("a blocked run gives up instead of holding the job")
    void aBlockedRunDoesNotHoldTheJob() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection lock = new DriverManagerDataSource(
                OWN.getJdbcUrl(), OWN.getUsername(), OWN.getPassword()).getConnection()) {
            lock.setAutoCommit(false);
            try (Statement s = lock.createStatement()) {
                s.execute("LOCK TABLE usage_charges IN ACCESS EXCLUSIVE MODE");
            }

            Future<?> blocked = pool.submit(() -> reconciliation.run());
            try {
                blocked.get(20, TimeUnit.SECONDS);
                fail("the run was expected to give up, not to succeed against a locked table");
            } catch (TimeoutException stillRunning) {
                fail("the run is still waiting on the lock, so the job is held");
            } catch (Exception gaveUp) {
                // Expected: bounded by a timeout rather than by the lock holder.
            }

            // 57014 is the database cancelling the statement, which is what
            // makes this a timeout rather than a coincidence, and it lands on
            // the run like any other failure.
            assertThat(superuser().queryForObject(
                "SELECT failure_code FROM reconciliation_runs ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo("57014");
            lock.rollback();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Order(3)
    @DisplayName("health believes the run that was recorded last, not the one that started last")
    void theRunHealthBelievesIsTheOneRecordedLast() {
        // A row is written when a run ends, so a run that started first and
        // blocked holds the higher id and the older start. Health has to follow
        // the id, or a short clean run that overtook it hides the failure.
        superuser().update("""
            INSERT INTO reconciliation_runs (started_at, finished_at, charges_checked,
                                             amount_mismatches, orphan_charges, unbalanced_txs)
            VALUES (now(), now(), 0, 0, 0, 0)
            """);
        superuser().update("""
            INSERT INTO reconciliation_runs (started_at, finished_at, failure_code)
            VALUES (now() - interval '2 minutes', now(), '57014')
            """);

        assertThat(status("/actuator/health")).contains("\"status\":\"DOWN\"");
    }

    @Test
    @Order(4)
    @DisplayName("a database that is down turns readiness red and leaves liveness green")
    void databaseDownIsReadinessNotLiveness() {
        assertThat(status("/actuator/health/readiness")).contains("\"status\":\"UP\"");

        OWN.stop();

        assertThat(status("/actuator/health/readiness")).contains("\"status\":\"DOWN\"");
        assertThat(status("/actuator/health/liveness")).contains("\"status\":\"UP\"");

        // The url the README hands a deployer. An indicator that throws instead
        // of answering takes this to 500 and no health document comes back.
        ResponseEntity<String> root = http.getForEntity("/actuator/health", String.class);
        assertThat(root.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(root.getBody()).contains("\"status\":\"DOWN\"");
    }
}
