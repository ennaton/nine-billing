package co.nine.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * reads none of the rows it owns.
 *
 * <p>It does not assert anything about a superuser, which still reads
 * everything. Nothing inside the database can change that, and saying so is
 * the point of writing it down here.
 *
 * <p>Nor does it claim the owner reads nothing anywhere. Seven tables carry
 * FORCE and four of them behave like accounts; the other three carry policies
 * that allow a read with no tenant on purpose, and the last case here pins that
 * difference rather than leaving it to be discovered.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationOwnerTest extends PostgresTestBase {

    static final List<String> TENANT_TABLES =
        List.of("accounts", "ledger_transactions", "postings", "usage_charges");

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

    @Test
    @Order(1)
    @DisplayName("setup: one tenant's usage event, which writes to all four tenant tables")
    void setup() {
        UUID tenant = UUID.randomUUID();
        HttpHeaders h = new HttpHeaders();
        h.set("X-Bootstrap-Secret", "test-bootstrap-secret");
        ResponseEntity<Map> minted = raw.postForEntity("/admin/keys",
            new HttpEntity<>(Map.of("tenantId", tenant, "label", "owner-test"), h), Map.class);
        assertThat(minted.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        HttpHeaders key = new HttpHeaders();
        key.set("X-Api-Key", (String) minted.getBody().get("apiKey"));
        ResponseEntity<Map> usage = raw.postForEntity("/v1/usage", new HttpEntity<>(Map.of(
            "eventId", "owner-1", "tenantId", tenant, "metric", "seats", "quantity", 3), key), Map.class);
        assertThat(usage.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Without this the zero below would be indistinguishable from an empty
        // database, which is the way this assertion fails without failing.
        for (String table : TENANT_TABLES) {
            assertThat(asSuperuser().queryForObject("SELECT count(*) FROM " + table, Long.class))
                .as("rows in %s, which the owner must then not see", table)
                .isPositive();
        }
    }

    @Test
    @Order(2)
    @DisplayName("the role Flyway migrates as is neither a superuser nor BYPASSRLS, and owns the tables")
    void theOwnerIsNotASuperuser() {
        Map<String, Object> role = asSuperuser().queryForMap(
            "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = ?", ownerUser);
        assertThat(role.get("rolsuper")).as("%s is a superuser, so FORCE ROW LEVEL SECURITY buys nothing", ownerUser)
            .isEqualTo(false);
        assertThat(role.get("rolbypassrls")).as("%s bypasses row-level security by attribute", ownerUser)
            .isEqualTo(false);

        List<String> owners = asSuperuser().queryForList("""
            SELECT DISTINCT pg_get_userbyid(c.relowner)
              FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = 'public' AND c.relkind = 'r'
            """, String.class);
        assertThat(owners).as("every table has to belong to the role FORCE applies to").containsExactly(ownerUser);
    }

    @Test
    @Order(3)
    @DisplayName("with no tenant bound the owner reads none of the rows in the four tenant tables")
    void theOwnerReadsNothingWithoutATenant() {
        for (String table : TENANT_TABLES) {
            assertThat(asOwner().queryForObject("SELECT count(*) FROM " + table, Long.class))
                .as("%s read as the owner with no tenant bound", table)
                .isZero();
        }
    }

    @Test
    @Order(4)
    @DisplayName("the owner's way out of RLS fails closed rather than opening the table")
    void theEscapeHatchRaisesInsteadOfBypassing() {
        // row_security = off is the documented way for an owner to step around
        // a policy. Against FORCE it raises instead, so the failure mode is an
        // error a caller sees rather than a silently wider read.
        // The root cause, because Spring's own message carries the statement
        // text and nothing else: asserting on the outer one passes for any
        // broken SQL, which is a test that cannot tell this failure apart.
        assertThatThrownBy(() -> asOwner().queryForObject(
            "SET row_security = off; SELECT count(*) FROM accounts", Long.class))
            .rootCause()
            .hasMessageContaining("query would be affected by row-level security policy");
    }

    @Test
    @Order(5)
    @DisplayName("the tables whose policy allows a read with no tenant still allow it, to the owner too")
    void thePolicyThatAllowsNoTenantStillDoes() {
        // FORCE binds the owner on all seven, but three of these policies let a
        // read with no tenant through deliberately: key lookup happens before a
        // tenant is known (V4's key_lookup), and the two reconciliation tables
        // hold no tenant data and carry USING (true). So the sentence above is
        // about the four tenant tables, and this is the other half of it. Only
        // api_keys is asserted, because the setup above is what put a row in it
        // and this suite shares one database.
        assertThat(asOwner().queryForObject("SELECT count(*) FROM api_keys", Long.class))
            .as("api_keys read as the owner with no tenant bound, which key_lookup allows on purpose")
            .isPositive();
    }
}
