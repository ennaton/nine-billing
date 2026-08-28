package co.nine.billing;

import co.nine.billing.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Isolation is a passing test on a reused connection, nothing less. These are
 * the four assertions from the RLS article, run against this schema as the
 * real application role, plus the HTTP layer's own refusals.
 *
 * <p>The app connects as nine_app (not superuser, not owner) and every table
 * has FORCE ROW LEVEL SECURITY, so a pass here is a pass under RLS for real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantIsolationTest extends PostgresTestBase {

    @Autowired TestRestTemplate raw;
    @Autowired JdbcTemplate jdbc;   // goes through TenantAwareDataSource, as nine_app

    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();
    static String keyA, keyB;
    static UUID txA;

    @AfterEach
    void unbind() { TenantContext.clear(); }

    TestRestTemplate as(String key) {
        return new TestRestTemplate(new RestTemplateBuilder().baseUri(raw.getRootUri()).defaultHeader("X-Api-Key", key));
    }

    String mint(UUID tenant) {
        HttpHeaders h = new HttpHeaders(); h.set("X-Bootstrap-Secret", "test-bootstrap-secret");
        ResponseEntity<Map> res = raw.postForEntity("/admin/keys",
            new HttpEntity<>(Map.of("tenantId", tenant, "label", "t"), h), Map.class);
        return (String) res.getBody().get("apiKey");
    }

    @Test @Order(1)
    @DisplayName("setup: two tenants, two keys, tenant A posts a charge")
    void setup() {
        keyA = mint(A); keyB = mint(B);
        ResponseEntity<Map> res = as(keyA).postForEntity("/v1/usage", Map.of(
            "eventId", "iso-1", "tenantId", A, "metric", "seats", "quantity", 1), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        txA = UUID.fromString((String) res.getBody().get("transactionId"));
    }

    // ---- database layer: the four assertions -----------------------------------

    @Test @Order(2)
    @DisplayName("DB 1: as tenant B, tenant A's rows do not exist (zero rows, not an error)")
    void otherTenantSeesNothing() {
        TenantContext.bind(B);
        Long n = jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE id = ?", Long.class, txA);
        assertThat(n).isZero();
        Long p = jdbc.queryForObject("SELECT count(*) FROM postings WHERE transaction_id = ?", Long.class, txA);
        assertThat(p).isZero();
    }

    @Test @Order(3)
    @DisplayName("DB 2: as tenant B, writing a row that claims tenant A is refused (42501)")
    void otherTenantCannotWrite() {
        TenantContext.bind(B);
        assertThatCode(() -> jdbc.update(
            "INSERT INTO accounts (id, tenant_id, code, type, currency) VALUES (?, ?, 'smuggled', 'ASSET', 'GBP')",
            UUID.randomUUID(), A))
            .rootCause().hasMessageContaining("row-level security");
    }

    @Test @Order(4)
    @DisplayName("DB 3: with no tenant bound, every table and the balances view is empty (fail-closed)")
    void noContextSeesNothing() {
        TenantContext.clear();   // the recycled-connection case: GUC left as ''
        Long a = jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class);
        Long t = jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Long.class);
        Long u = jdbc.queryForObject("SELECT count(*) FROM usage_charges", Long.class);
        // A view runs with its owner's privileges unless it is security_invoker,
        // which is how account_balances stayed outside this assertion while
        // returning every tenant's rows. V6 set the option; this line is what
        // stops it coming back.
        Long b = jdbc.queryForObject("SELECT count(*) FROM account_balances", Long.class);
        assertThat(a).isZero(); assertThat(t).isZero(); assertThat(u).isZero();
        assertThat(b).isZero();
    }

    @Test @Order(5)
    @DisplayName("DB 4: no tenant bound does NOT raise 22P02; the predicate is NULL, not an error")
    void noContextDoesNotThrow() {
        TenantContext.clear();
        assertThatCode(() -> jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Long.class))
            .doesNotThrowAnyException();
    }

    @Test @Order(6)
    @DisplayName("DB 5: tenant A still sees its own rows (RLS filters, it does not hide everything)")
    void ownerStillSees() {
        TenantContext.bind(A);
        Long n = jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE id = ?", Long.class, txA);
        assertThat(n).isEqualTo(1);
    }

    @Test @Order(7)
    @DisplayName("DB 6: with a tenant bound, the balances view agrees with the table it derives from")
    void viewAgreesWithItsTable() {
        TenantContext.bind(A);
        Long rows = jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class);
        Long view = jdbc.queryForObject("SELECT count(*) FROM account_balances", Long.class);
        assertThat(view).isEqualTo(rows);
    }

    // ---- http layer ---------------------------------------------------------------

    @Test @Order(8)
    @DisplayName("HTTP: no key is 401 problem+json")
    void noKeyIs401() {
        ResponseEntity<Map> res = raw.getForEntity("/v1/tenants/" + A + "/balance", Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getHeaders().getContentType().toString()).contains("problem+json");
    }

    @Test @Order(9)
    @DisplayName("HTTP: a wrong key is 401, same body, no hint whether the key format was close")
    void wrongKeyIs401() {
        ResponseEntity<Map> res = as("nk_definitely-not-a-key").getForEntity("/v1/tenants/" + A + "/balance", Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test @Order(10)
    @DisplayName("HTTP: tenant B asking for tenant A's balance is 404, not 403: existence is not disclosed")
    void crossTenantIs404() {
        ResponseEntity<Map> res = as(keyB).getForEntity("/v1/tenants/" + A + "/balance", Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> ledger = as(keyB).getForEntity("/v1/tenants/" + A + "/ledger", Map.class);
        assertThat(ledger.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @Order(11)
    @DisplayName("HTTP: tenant B cannot post usage in tenant A's name, and cannot reverse A's transaction")
    void crossTenantWritesAre404() {
        ResponseEntity<Map> usage = as(keyB).postForEntity("/v1/usage", Map.of(
            "eventId", "iso-2", "tenantId", A, "metric", "seats", "quantity", 1), Map.class);
        assertThat(usage.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> rev = as(keyB).postForEntity("/v1/ledger/" + txA + "/reverse", Map.of(
            "tenantId", A, "idempotencyKey", "iso-rev", "reason", "theft"), Map.class);
        assertThat(rev.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // and nothing moved
        ResponseEntity<Map> bal = as(keyA).getForEntity("/v1/tenants/" + A + "/balance", Map.class);
        assertThat(bal.getBody()).containsEntry("owedMinor", 900);
    }

    @Test @Order(12)
    @DisplayName("HTTP: even with a valid key for B, naming B in the body but A in the path is 404")
    void mixedIdentityIs404() {
        // The guard checks the tenant the request names against the key's tenant, every time.
        ResponseEntity<Map> rev = as(keyB).postForEntity("/v1/ledger/" + txA + "/reverse", Map.of(
            "tenantId", B, "idempotencyKey", "iso-rev-2", "reason", "mixed"), Map.class);
        // tenant matches the key, but txA is A's: RLS hides it, the service finds no postings -> 404
        assertThat(rev.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @Order(13)
    @DisplayName("reconciliation is operator only: a tenant key cannot run it or read findings")
    void reconciliationIsOperatorOnly() {
        // Was /v1/reconciliation/run, reachable by any valid key. It is now an
        // operator endpoint, and a tenant key is not an operator credential.
        assertThat(as(keyA).postForEntity("/v1/reconciliation/run", null, Map.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);

        HttpHeaders h = new HttpHeaders();
        h.set("X-Bootstrap-Secret", "test-bootstrap-secret");
        ResponseEntity<Map> run = raw.exchange("/admin/reconciliation/run",
            org.springframework.http.HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The counts a tenant may see carry no tenant identity.
        ResponseEntity<String> runs = as(keyA).getForEntity("/v1/reconciliation/runs", String.class);
        assertThat(runs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(runs.getBody()).doesNotContain(B.toString());
    }
}
