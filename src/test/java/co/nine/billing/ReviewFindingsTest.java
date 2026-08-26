package co.nine.billing;

import co.nine.billing.auth.TenantContext;
import co.nine.billing.metering.MeteringService;
import co.nine.billing.metering.UsageEvent;
import co.nine.billing.reconciliation.ReconciliationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two findings the review marked P0, each stated as a test before it is
 * fixed. A finding that cannot be written as a failing test is a finding
 * nobody has understood yet.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReviewFindingsTest extends PostgresTestBase {

    @Autowired TestRestTemplate raw;
    @Autowired MeteringService metering;
    @Autowired ReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;

    @AfterEach void unbind() { TenantContext.clear(); }

    String mint(UUID tenant) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Bootstrap-Secret", "test-bootstrap-secret");
        ResponseEntity<Map> res = raw.postForEntity("/admin/keys",
            new HttpEntity<>(Map.of("tenantId", tenant, "label", "review"), h), Map.class);
        return (String) res.getBody().get("apiKey");
    }

    TestRestTemplate as(String key) {
        return new TestRestTemplate(new RestTemplateBuilder().rootUri(raw.getRootUri()).defaultHeader("X-Api-Key", key));
    }

    // ---- T1 -------------------------------------------------------------

    @Test
    @DisplayName("T1: a tenant must not read another tenant's reconciliation findings")
    void reconciliationFindingsAreNotCrossTenant() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        String keyA = mint(a), keyB = mint(b);

        // Tenant A has a charge, and the ledger is tampered with so the run
        // produces a finding that names A: tenant id, event id, amounts.
        TenantContext.bind(a);
        UUID txA = metering.charge(new UsageEvent("t1-evt", a, "seats", 2, Instant.now())).transactionId();
        TenantContext.clear();

        JdbcTemplate su = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        su.execute("ALTER TABLE postings DISABLE TRIGGER postings_immutable");
        su.execute("ALTER TABLE postings DISABLE TRIGGER postings_balance_check");
        su.update("UPDATE postings SET amount_minor = amount_minor + 7 WHERE transaction_id = ? AND direction = 'DEBIT'", txA);
        su.execute("ALTER TABLE postings ENABLE TRIGGER postings_immutable");
        su.execute("ALTER TABLE postings ENABLE TRIGGER postings_balance_check");

        var report = reconciliation.run();
        long runId = report.runId();

        // Preconditions, asserted rather than assumed. Without these the test
        // below passes whenever the run is empty or the request errors, which
        // is a test that proves nothing. The first version of this test did
        // exactly that.
        assertThat(report.findings())
            .as("the tampering must actually produce findings, or this test is vacuous")
            .isNotEmpty();
        assertThat(String.valueOf(report.findings()))
            .as("the findings must name tenant A, or there is nothing to leak")
            .contains(a.toString());

        // Tenant B holds a perfectly valid key of its own.
        ResponseEntity<String> viaV1 = as(keyB).getForEntity(
            "/v1/reconciliation/runs/" + runId + "/findings", String.class);
        assertThat(viaV1.getStatusCode())
            .as("the tenant facing surface must not serve findings at all")
            .isEqualTo(HttpStatus.NOT_FOUND);

        // The operator surface needs the operator secret, not a tenant key.
        ResponseEntity<String> viaAdminNoSecret = raw.getForEntity(
            "/admin/reconciliation/runs/" + runId + "/findings", String.class);
        assertThat(viaAdminNoSecret.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // And the counts a tenant may legitimately see carry no identity.
        ResponseEntity<String> runs = as(keyB).getForEntity("/v1/reconciliation/runs", String.class);
        assertThat(runs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(runs.getBody())
            .as("run summaries must not name a tenant: %s", runs.getBody())
            .doesNotContain(a.toString())
            .doesNotContain(txA.toString())
            .doesNotContain("t1-evt");
    }

    // ---- T2 -------------------------------------------------------------

    @Test
    @DisplayName("T2: a zero decimal currency must format without a decimal point and must not 500")
    void zeroDecimalCurrencyFormats() {
        UUID t = UUID.randomUUID();
        String key = mint(t);

        ResponseEntity<Map> res = as(key).getForEntity("/v1/tenants/" + t + "/balance?currency=JPY", Map.class);

        assertThat(res.getStatusCode()).as("JPY balance must not be a server error").isEqualTo(HttpStatus.OK);
        assertThat((String) res.getBody().get("display"))
            .as("JPY has no minor unit, so no decimal point belongs in the display")
            .doesNotContain(".")
            .contains("JPY");
    }
}
