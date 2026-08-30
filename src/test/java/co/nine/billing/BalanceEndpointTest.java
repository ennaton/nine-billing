package co.nine.billing;

import co.nine.billing.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a balance is a read.
 *
 * <p>It did not used to be. {@code owed} called {@code ensureAccount}, so a GET
 * inserted a row, outside any transaction, and the account key is
 * {@code (tenant, code)} rather than {@code (tenant, code, currency)}. Whatever
 * currency the first read happened to name became the one the tenant was stuck
 * with, and {@code nine_app} holds no UPDATE or DELETE grant on {@code accounts},
 * so nothing in the service could undo it. One GET with the wrong query parameter
 * ended a tenant's ability to be billed.
 *
 * <p>Each test mints its own tenant, so nothing here depends on ordering.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class BalanceEndpointTest extends PostgresTestBase {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
        new ParameterizedTypeReference<>() {};

    @Autowired TestRestTemplate raw;
    @Autowired JdbcTemplate jdbc;

    UUID tenant;
    TestRestTemplate http;

    @BeforeEach
    void mintTenant() {
        tenant = UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Bootstrap-Secret", "test-bootstrap-secret");
        ResponseEntity<Map<String, Object>> minted = raw.exchange("/admin/keys", HttpMethod.POST,
            new HttpEntity<>(Map.of("tenantId", tenant, "label", "balance"), headers), JSON);
        http = new TestRestTemplate(new RestTemplateBuilder()
            .baseUri(raw.getRootUri())
            .defaultHeader("X-Api-Key", (String) minted.getBody().get("apiKey")));
    }

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    private ResponseEntity<Map<String, Object>> balance(String currency) {
        return http.exchange("/v1/tenants/" + tenant + "/balance?currency=" + currency,
            HttpMethod.GET, null, JSON);
    }

    private long accountsOf(UUID owner) {
        TenantContext.bind(owner);
        try {
            return jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("reading a balance creates nothing")
    void readingABalanceWritesNothing() {
        assertThat(accountsOf(tenant))
            .as("a tenant that has never been charged starts with no accounts")
            .isZero();

        ResponseEntity<Map<String, Object>> read = balance("USD");
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody()).containsEntry("owedMinor", 0);

        assertThat(accountsOf(tenant))
            .as("the read must not have decided anything about this tenant")
            .isZero();
    }

    @Test
    @DisplayName("a balance read with the wrong currency does not end the tenant's billing")
    void aReadCannotLockTheTenantsCurrency() {
        // USD is a perfectly valid ISO 4217 code, so no amount of validating the
        // parameter would have caught this. The damage was the write, not the value.
        assertThat(balance("USD").getStatusCode()).isEqualTo(HttpStatus.OK);

        // Every price plan is in GBP. Before this, the read above had already
        // created a USD receivable, and every GBP posting then failed against the
        // composite foreign key postings_currency_matches_account, permanently.
        ResponseEntity<Map<String, Object>> charge = http.exchange("/v1/usage", HttpMethod.POST,
            new HttpEntity<>(Map.of("eventId", "after-a-read", "tenantId", tenant,
                "metric", "agent_seconds", "quantity", 10)), JSON);

        assertThat(charge.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(charge.getBody()).containsEntry("chargedMinor", 20);
    }
}
