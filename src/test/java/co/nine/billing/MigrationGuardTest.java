package co.nine.billing;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BI12.5's guard, which is a Flyway callback rather than a versioned migration.
 *
 * <p>A versioned check runs once. Put `NINE_MIGRATION_USERNAME` back to a
 * superuser afterwards and every table the next migration creates belongs to one
 * again, with nothing failing and nothing said. `BEFORE_MIGRATE` fires at the top
 * of every `migrate()`, so the second case here runs it with nothing pending: if
 * that assumption about Flyway were wrong, this test would go green while the
 * guard was asleep.
 *
 * <p>Its own container, because the third case takes a table away from its owner
 * and the suite's shared database would carry that to whatever ran next.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationGuardTest {

    static final PostgreSQLContainer OWN = new PostgreSQLContainer("postgres:16-alpine")
        .withCopyFileToContainer(MountableFile.forClasspathResource("db/bootstrap.sql"),
                                 "/docker-entrypoint-initdb.d/20-bootstrap.sql");

    static {
        OWN.start();
    }

    static Flyway flywayAs(String user, String password) {
        return Flyway.configure()
            .dataSource(OWN.getJdbcUrl(), user, password)
            .locations("classpath:db/migration")
            .placeholders(Map.of("operatorPassword", "op_dev", "appPassword", "nine_app_dev"))
            .load();
    }

    static JdbcTemplate superuser() {
        return new JdbcTemplate(new DriverManagerDataSource(
            OWN.getJdbcUrl(), OWN.getUsername(), OWN.getPassword()));
    }

    @Test
    @Order(1)
    @DisplayName("the owner the bootstrap created migrates the database")
    void theOwnerMigrates() {
        assertThat(flywayAs("nine_owner", "nine_owner_dev").migrate().migrationsExecuted).isPositive();
    }

    @Test
    @Order(2)
    @DisplayName("the callback runs again with nothing pending, which is why it is not a migration")
    void theCallbackRunsWithNothingPending() {
        assertThat(flywayAs("nine_owner", "nine_owner_dev").migrate().migrationsExecuted)
            .as("something was still pending, so this case proves nothing about the callback")
            .isZero();

        // Same call, superuser credentials, still nothing pending. A versioned
        // migration would have been applied already and would stay quiet here.
        assertThatThrownBy(() -> flywayAs(OWN.getUsername(), OWN.getPassword()).migrate())
            .hasStackTraceContaining("bypasses row level security");
    }

    @Test
    @Order(3)
    @DisplayName("a bootstrapped database whose tables never moved is refused too")
    void tablesLeftBehindAreRefused() {
        // The state finding 1 of the review describes: connected as nine_owner,
        // so current_user alone reports this database as healthy, while the
        // table that carries FORCE still belongs to a role that ignores it.
        superuser().execute("ALTER TABLE accounts OWNER TO " + OWN.getUsername());
        try {
            assertThatThrownBy(() -> flywayAs("nine_owner", "nine_owner_dev").migrate())
                .hasStackTraceContaining("FORCE ROW LEVEL SECURITY");
        } finally {
            superuser().execute("ALTER TABLE accounts OWNER TO nine_owner");
        }

        assertThatCode(() -> flywayAs("nine_owner", "nine_owner_dev").migrate())
            .as("the guard has to pass again once the table is back where it belongs")
            .doesNotThrowAnyException();
    }
}
