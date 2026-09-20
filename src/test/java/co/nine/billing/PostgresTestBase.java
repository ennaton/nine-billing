package co.nine.billing;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * One Postgres for the whole suite, with the role setup the service runs under
 * in production: Flyway migrates as nine_owner, the application connects as
 * nine_app, and neither is a superuser. Tests therefore exercise row-level
 * security for real; a test passing here means it passed under RLS, and it
 * passed against an owner RLS applies to.
 *
 * <p>The bootstrap goes in through the image's own init directory rather than
 * withInitScript, because it is dollar-quoted and that path hands the file to
 * psql instead of to a splitter that breaks on the semicolons inside a DO
 * block. The compose stack uses the same directory but not this file: it has a
 * hand copy of three of these statements, so what is proven here is the script
 * and what runs there is the copy.
 */
public abstract class PostgresTestBase {

    // Testcontainers 2.0 dropped the self-referential generic: the class is
    // no longer PostgreSQLContainer<SELF>, so the wildcard and the diamond go.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withCopyFileToContainer(MountableFile.forClasspathResource("db/bootstrap.sql"),
                                 "/docker-entrypoint-initdb.d/20-bootstrap.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        // runtime: nine_app (created by V4)
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "nine_app");
        // No password here on purpose: V12 sets it from the same default the
        // datasource resolves, so a copy would hide a drift between the two.

        // migrations: nine_owner, created by the bootstrap above. No user or
        // password here either, for the same reason: application.yml holds the
        // development default and a copy would hide a drift from bootstrap.sql.
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("nine.billing.reconcile.interval", () -> "PT24H");
        r.add("nine.billing.bootstrap-secret", () -> "test-bootstrap-secret");
    }
}
