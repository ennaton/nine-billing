package co.nine.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import co.nine.billing.infrastructure.ConstraintRules;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a caller is told when the database refuses a write.
 *
 * <p>The ledger's guarantees live in constraints, so a caller meets them
 * regularly and by design. What reaches them was the Postgres message verbatim:
 * an index name, a table name, sometimes a column type. That is a contract
 * nobody agreed to. Renaming an index would break a client, and the client had
 * nothing better to key on because the rule itself was never named.
 *
 * <p>Each test mints its own tenant, so nothing here depends on ordering.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ConstraintErrorsTest extends PostgresTestBase {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
        new ParameterizedTypeReference<>() {};

    @Autowired TestRestTemplate raw;

    UUID tenant;
    TestRestTemplate http;

    @BeforeEach
    void mintTenant() {
        tenant = UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Bootstrap-Secret", "test-bootstrap-secret");
        ResponseEntity<Map<String, Object>> minted = raw.exchange("/admin/keys", HttpMethod.POST,
            new HttpEntity<>(Map.of("tenantId", tenant, "label", "constraints"), headers), JSON);
        http = new TestRestTemplate(new RestTemplateBuilder()
            .baseUri(raw.getRootUri())
            .defaultHeader("X-Api-Key", (String) minted.getBody().get("apiKey")));
    }

    @Test
    @DisplayName("a refused write names the rule, not the index that enforced it")
    void aRefusedWriteNamesTheRule() {
        ResponseEntity<Map<String, Object>> charge = http.exchange("/v1/usage", HttpMethod.POST,
            new HttpEntity<>(Map.of("eventId", "e", "tenantId", tenant,
                "metric", "agent_seconds", "quantity", 10)), JSON);
        assertThat(charge.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tx = UUID.fromString((String) charge.getBody().get("transactionId"));

        assertThat(reverse(tx, "first").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // A different key, so this is not a replay: it is a second reversal of a
        // transaction that has one, which the partial unique index refuses.
        ResponseEntity<Map<String, Object>> second = reverse(tx, "second");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        String detail = (String) second.getBody().get("detail");
        assertThat(detail)
            .as("the caller is told which rule they broke")
            .containsIgnoringCase("already");
        assertThat(detail)
            .as("and not which index enforces it, which is ours to rename")
            .doesNotContain("ledger_tx_reverses_unique")
            .doesNotContain("duplicate key value")
            .doesNotContain("ERROR:");
    }

    /**
     * The two rows of the table that carry no constraint name, plus one that
     * shares their SQLState and does.
     *
     * <p>A {@code RAISE} inside a trigger is not a constraint violation, so
     * Postgres has nothing to put in the constraint field. Those rows are matched
     * on SQLState with a null constraint, which only works if the match checks
     * both halves: {@code postings_amount_minor_check} is also 23514, and a
     * lookup that ignored the constraint name would answer it with the wrong
     * rule. Driven through real failures rather than constructed exceptions,
     * because the point is what the server actually sends.
     */
    @Test
    @DisplayName("the rules a trigger raises are told apart from the constraint that shares their state")
    void triggerRaisesAreMappedByStateAndNullConstraint() {
        JdbcTemplate su = superuser();
        UUID tenantB = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID tx = UUID.randomUUID();
        su.update("INSERT INTO accounts (id, tenant_id, code, type, currency) VALUES (?,?,?,?,?)",
            account, tenantB, "cash", "ASSET", "GBP");
        su.update("INSERT INTO ledger_transactions (id, tenant_id, idempotency_key, description, occurred_at)"
            + " VALUES (?,?,?,?, now())", tx, tenantB, "rules-probe", "probe");
        su.update("INSERT INTO postings (transaction_id, account_id, direction, amount_minor, currency)"
            + " VALUES (?,?,'DEBIT',100,'GBP'), (?,?,'CREDIT',100,'GBP')", tx, account, tx, account);

        assertThat(ruleFor(() -> su.update(
            "UPDATE postings SET amount_minor = 1 WHERE transaction_id = ?", tx)))
            .as("23001 with no constraint name")
            .contains(ConstraintRules.Rule.IMMUTABLE);

        assertThat(ruleFor(() -> su.update(
            "INSERT INTO postings (transaction_id, account_id, direction, amount_minor, currency)"
                + " VALUES (?,?,'DEBIT',1,'GBP')", tx, account)))
            .as("23514 with no constraint name, raised by the balance trigger")
            .contains(ConstraintRules.Rule.DOES_NOT_BALANCE);

        assertThat(ruleFor(() -> su.update(
            "INSERT INTO postings (transaction_id, account_id, direction, amount_minor, currency)"
                + " VALUES (?,?,'DEBIT',0,'GBP')", tx, account)))
            .as("23514 with a constraint name, which must not be confused with the trigger")
            .contains(ConstraintRules.Rule.AMOUNT_NOT_POSITIVE);
    }

    private java.util.Optional<ConstraintRules.Rule> ruleFor(Runnable write) {
        try {
            write.run();
            throw new AssertionError("the database was expected to refuse this write");
        } catch (RuntimeException refused) {
            return ConstraintRules.of(refused);
        }
    }

    /** Superuser connection, outside the pool and outside RLS: the only way to reach these. */
    private JdbcTemplate superuser() {
        return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private ResponseEntity<Map<String, Object>> reverse(UUID tx, String key) {
        return http.exchange("/v1/ledger/" + tx + "/reverse", HttpMethod.POST,
            new HttpEntity<>(Map.of("tenantId", tenant, "idempotencyKey", key, "reason", "test")), JSON);
    }
}
