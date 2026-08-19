package co.nine.billing;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres for the whole suite, with the two-role setup the service runs
 * under in production: Flyway migrates as the container's superuser, the
 * application connects as nine_app. Tests therefore exercise row-level
 * security for real; a test passing here means it passed under RLS.
 */
public abstract class PostgresTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        // runtime: nine_app (created by V4)
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "nine_app");
        r.add("spring.datasource.password", () -> "nine_app_dev");
        // migrations: owner
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("nine.billing.reconcile.interval", () -> "PT24H");
        r.add("nine.billing.bootstrap-secret", () -> "test-bootstrap-secret");
    }
}
