package co.nine.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BI12.5. Seven tables carry FORCE ROW LEVEL SECURITY, and FORCE only means
 * something when the owner is not a superuser: a superuser bypasses row-level
 * security whatever the table says. The owner was postgres, so those seven
 * statements bought nothing, and the claim in CLAUDE.md and application.yml
 * that the runtime role must be neither superuser nor owner rested on half a
 * boundary.
 *
 * <p>db/bootstrap.sql creates nine_owner and hands it the database; Flyway
 * connects as it. This asserts what that buys, against the credential the
 * application itself resolved rather than a copy of it: the role is neither a
 * superuser nor BYPASSRLS, it owns the tables, and with no tenant bound it
 * reads none of the rows in five of the seven.
 *
 * <p>It asserts nothing about a superuser, which still reads everything.
 * Nothing inside the database can change that, and saying so is the point of
 * writing it down here.
 *
 * <p>Two of the seven let the owner read: api_keys, because a key is looked up
 * before any tenant is known, and reconciliation_runs, because it holds counts
 * rather than tenant rows. reconciliation_findings looks like a third and is
 * not. V4 gave it USING (true); V5 took that away and said in its own header
 * that the comment claiming these tables hold no tenant data was wrong, since
 * findings carries tenant_id. Both halves are asserted with rows present,
 * because zero from an empty table is not a measurement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class MigrationOwnerTest extends PostgresTestBase {

    /** With no tenant bound the owner reads none of these. */
    static final List<String> CLOSED =
        List.of("accounts", "ledger_transactions", "postings", "usage_charges", "reconciliation_findings");

    /** And these two let it through, by a policy that means to. */
    static final List<String> OPEN = List.of("api_keys", "reconciliation_runs");

    /** Fixed, so every case seeds the same rows and none of them needs an order. */
    static final UUID TENANT = UUID.fromString("0b7d5a1e-9f3c-4a77-8a1d-5c2e6f0b91aa");
    static String apiKey;

    @Autowired TestRestTemplate raw;

    @Value("${spring.flyway.user}") String ownerUser;
    @Value("${spring.flyway.password}") String ownerPassword;

    /** The migration credential itself, outside the app's pool and its tenant binding. */
    JdbcTemplate asOwner() {
        return new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), ownerUser, ownerPassword));
    }

    /** The container's superuser, which is how the rows are proved to be there at all. */
    JdbcTemplate asSuperuser() {
        return new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void seed() {
        // Idempotent, so this class needs no method order and every case can be
        // run on its own: the usage endpoint keys on eventId, and the operator
        // rows are written only when they are not already there.
        if (apiKey == null) {
            HttpHeaders bootstrap = new HttpHeaders();
            bootstrap.set("X-Bootstrap-Secret", "test-bootstrap-secret");
            ResponseEntity<Map> minted = raw.postForEntity("/admin/keys",
                new HttpEntity<>(Map.of("tenantId", TENANT, "label", "owner-test"), bootstrap), Map.class);
            assertThat(minted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            apiKey = (String) minted.getBody().get("apiKey");
        }

        HttpHeaders key = new HttpHeaders();
        key.set("X-Api-Key", apiKey);
        ResponseEntity<Map> usage = raw.postForEntity("/v1/usage", new HttpEntity<>(Map.of(
            "eventId", "owner-1", "tenantId", TENANT, "metric", "seats", "quantity", 3), key), Map.class);
        // 201 the first time and 200 after it: the endpoint keys on eventId,
        // which is what makes seeding in a @BeforeEach safe rather than a way
        // to accumulate rows.
        assertThat(usage.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);

        // No HTTP call in this class writes a finding, and a finding is the row
        // that tells the two reconciliation tables apart.
        if (asSuperuser().queryForObject("SELECT count(*) FROM reconciliation_findings", Long.class) == 0) {
            Long runId = asSuperuser().queryForObject(
                "INSERT INTO reconciliation_runs (started_at, finished_at, charges_checked,"
                    + " amount_mismatches, orphan_charges, unbalanced_txs)"
                    + " VALUES (now(), now(), 1, 1, 0, 0) RETURNING id", Long.class);
            asSuperuser().update(
                "INSERT INTO reconciliation_findings (run_id, kind, tenant_id, detail)"
                    + " VALUES (?, 'AMOUNT_MISMATCH', ?, 'seeded by MigrationOwnerTest')",
                runId, TENANT);
        }

        // Without this the zeros below would be indistinguishable from an empty
        // database, which is how this assertion would fail without failing.
        for (String table : CLOSED) {
            assertThat(asSuperuser().queryForObject("SELECT count(*) FROM " + table, Long.class))
                .as("rows in %s, which the owner must then not see", table)
                .isPositive();
        }
        for (String table : OPEN) {
            assertThat(asSuperuser().queryForObject("SELECT count(*) FROM " + table, Long.class))
                .as("rows in %s, which the owner is then expected to see", table)
                .isPositive();
        }
    }

    @Test
    @DisplayName("the role Flyway migrates as is neither a superuser nor BYPASSRLS, and owns the tables")
    void theOwnerIsNotASuperuser() {
        Map<String, Object> role = asSuperuser().queryForMap(
            "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = ?", ownerUser);
        assertThat(role.get("rolsuper")).as("%s is a superuser, so FORCE ROW LEVEL SECURITY buys nothing", ownerUser)
            .isEqualTo(false);
        assertThat(role.get("rolbypassrls")).as("%s bypasses row-level security by attribute", ownerUser)
            .isEqualTo(false);

        List<String> owners = asSuperuser().queryForList(
            "SELECT DISTINCT pg_get_userbyid(c.relowner) FROM pg_class c"
                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = 'public' AND c.relkind = 'r'", String.class);
        assertThat(owners).as("every table has to belong to the role FORCE applies to").containsExactly(ownerUser);
    }

    @Test
    @DisplayName("with no tenant bound the owner reads none of the rows in five of the seven")
    void theOwnerReadsNothingWithoutATenant() {
        for (String table : CLOSED) {
            assertThat(asOwner().queryForObject("SELECT count(*) FROM " + table, Long.class))
                .as("%s read as the owner with no tenant bound", table)
                .isZero();
        }
    }

    @Test
    @DisplayName("the owner's way out of RLS fails closed rather than opening the table")
    void theEscapeHatchRaisesInsteadOfBypassing() {
        // row_security = off is the documented way for an owner to step around
        // a policy. Against FORCE it raises instead, so the failure mode is an
        // error a caller sees rather than a silently wider read.
        //
        // The root cause, because Spring's own message carries the statement
        // text and nothing else: asserting on the outer one passes for any
        // broken SQL, which is a test that cannot tell this failure apart.
        assertThatThrownBy(() -> asOwner().queryForObject(
            "SET row_security = off; SELECT count(*) FROM accounts", Long.class))
            .rootCause()
            .hasMessageContaining("query would be affected by row-level security policy");
    }

    @Test
    @DisplayName("the two whose policy allows a read with no tenant still allow it, to the owner too")
    void thePolicyThatAllowsNoTenantStillDoes() {
        // FORCE binds the owner on all seven, and on these two the policy still
        // says yes: key lookup happens before a tenant is known (V4's
        // key_lookup) and reconciliation_runs holds counts rather than tenant
        // rows (V5's runs_read_any). The difference from the five above is only
        // visible when there is something to read, which the setup guarantees.
        for (String table : OPEN) {
            assertThat(asOwner().queryForObject("SELECT count(*) FROM " + table, Long.class))
                .as("%s read as the owner with no tenant bound, which its policy allows on purpose", table)
                .isPositive();
        }
    }
}
