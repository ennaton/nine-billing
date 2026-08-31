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

    private void openAccount(String code, String type, String currency) {
        TenantContext.bind(tenant);
        try {
            jdbc.update("INSERT INTO accounts (id, tenant_id, code, type, currency) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), tenant, code, type, currency);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("a tenant with two currencies is answered in the one that was asked for")
    void twoCurrenciesAreAnsweredSeparately() {
        // Charge once. Every price plan is GBP, so this opens a GBP receivable.
        ResponseEntity<Map<String, Object>> charge = http.exchange("/v1/usage", HttpMethod.POST,
            new HttpEntity<>(Map.of("eventId", "two-books", "tenantId", tenant,
                "metric", "agent_seconds", "quantity", 10)), JSON);
        assertThat(charge.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // A second book for the same role. This is what BI13.3 makes possible and
        // the old key made impossible: one tenant, one code, two currencies.
        openAccount("receivable", "ASSET", "USD");
        assertThat(accountsOf(tenant))
            .as("the tenant now holds a receivable in each currency")
            .isEqualTo(3);

        // The currency names which book to read, it does not relabel whichever one
        // the lookup happened to reach first. Without that, this endpoint reports a
        // GBP balance as USD, which is the harm D3 was about, reappearing through a
        // different door once the key allows two accounts.
        ResponseEntity<Map<String, Object>> gbp = balance("GBP");
        assertThat(gbp.getBody()).containsEntry("owedMinor", 20);
        assertThat(gbp.getBody()).containsEntry("currency", "GBP");

        ResponseEntity<Map<String, Object>> usd = balance("USD");
        assertThat(usd.getBody())
            .as("nothing was ever charged in USD, so the answer is zero rather than the GBP figure")
            .containsEntry("owedMinor", 0);
        assertThat(usd.getBody()).containsEntry("currency", "USD");
    }

    @Test
    @DisplayName("a currency that does not exist is a bad request, and opens no account")
    void anUnknownCurrencyIsRefused() {
        // Charge first, so the tenant has a real balance. A wrong answer here
        // would then be a wrong number rather than an empty one.
        http.exchange("/v1/usage", HttpMethod.POST,
            new HttpEntity<>(Map.of("eventId", "before-a-bad-read", "tenantId", tenant,
                "metric", "agent_seconds", "quantity", 10)), JSON);
        long before = accountsOf(tenant);

        ResponseEntity<Map<String, Object>> res = balance("AAA");

        assertThat(res.getStatusCode())
            .as("ISO 4217 is a closed set, so a code outside it is a malformed request")
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getHeaders().getContentType())
            .as("errors on this API are problem+json")
            .hasToString("application/problem+json");
        assertThat(res.getBody()).containsEntry("title", "Unknown currency");
        assertThat(accountsOf(tenant))
            .as("a refused read is still a read")
            .isEqualTo(before);
    }

    @Test
    @DisplayName("a GBP balance is not relabelled JPY by asking for JPY")
    void aBalanceIsNeverRelabelled() {
        // Every price plan is GBP, so this is a GBP balance of 20 minor units.
        http.exchange("/v1/usage", HttpMethod.POST,
            new HttpEntity<>(Map.of("eventId", "gbp-only", "tenantId", tenant,
                "metric", "agent_seconds", "quantity", 10)), JSON);
        long before = accountsOf(tenant);

        // JPY rather than USD on purpose. JPY reports zero fraction digits, so
        // display() takes the branch that skips the divide. A relabelled balance
        // would come back as "20 JPY", which reads as twenty yen for twenty
        // pence: the same figure inflated a hundredfold, not merely mislabelled.
        ResponseEntity<Map<String, Object>> jpy = balance("JPY");

        assertThat(jpy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jpy.getBody())
            .as("nothing was charged in JPY, so the answer is zero and not the GBP figure")
            .containsEntry("owedMinor", 0);
        assertThat(jpy.getBody()).containsEntry("currency", "JPY");
        assertThat(jpy.getBody())
            .as("zero yen, printed without a decimal point that yen do not have")
            .containsEntry("display", "0 JPY");

        // The GBP book is untouched by having been asked about in another currency.
        assertThat(balance("GBP").getBody())
            .containsEntry("owedMinor", 20)
            .containsEntry("display", "0.20 GBP");
        assertThat(accountsOf(tenant))
            .as("asking in a currency the tenant does not hold opens no book")
            .isEqualTo(before);
    }

    @Test
    @DisplayName("a quantity that would overflow the price is refused, not a 500")
    void anAbsurdQuantityIsRefused() {
        // seats is priced at 900 minor units, so Long.MAX_VALUE here reaches
        // Math.multiplyExact in PricePlan and throws ArithmeticException, which
        // no handler maps. The caller sees 500 for a request that is simply out
        // of range, and a 500 tells a client to retry something that will never
        // succeed.
        ResponseEntity<Map<String, Object>> res = http.exchange("/v1/usage", HttpMethod.POST,
            new HttpEntity<>(Map.of("eventId", "absurd", "tenantId", tenant,
                "metric", "seats", "quantity", Long.MAX_VALUE)), JSON);

        assertThat(res.getStatusCode())
            .as("out of range is the caller's problem, not the server's")
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a quantity inside the ceiling is still charged")
    void aLargeButLegitimateQuantityStillWorks() {
        // The event contract caps duration_ms at 86,400,000, so the largest
        // quantity any metric can legitimately carry is a 24 hour run in
        // seconds. This is comfortably inside that and must not be refused.
        ResponseEntity<Map<String, Object>> res = http.exchange("/v1/usage", HttpMethod.POST,
            new HttpEntity<>(Map.of("eventId", "big-but-real", "tenantId", tenant,
                "metric", "agent_seconds", "quantity", 86_400)), JSON);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).containsEntry("chargedMinor", 172_800);
    }
}
