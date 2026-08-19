package co.nine.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The metering surface, driven exactly the way a client would drive it: over
 * HTTP, against a real Postgres. This is the Postman session, automated.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MeteringHttpTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TestRestTemplate http;

    static final UUID TENANT = UUID.randomUUID();
    static UUID firstTx;

    @Test @Order(1)
    @DisplayName("POST /v1/usage prices an event and returns 201 with the charge")
    void usageIsCharged() {
        ResponseEntity<Map> res = http.postForEntity("/v1/usage", Map.of(
            "eventId", "evt-1", "tenantId", TENANT, "metric", "agent_seconds", "quantity", 120), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).containsEntry("chargedMinor", 240)   // 120 s x 2 minor
                                 .containsEntry("currency", "GBP")
                                 .containsEntry("replayed", false);
        firstTx = UUID.fromString((String) res.getBody().get("transactionId"));
    }

    @Test @Order(2)
    @DisplayName("the same eventId again returns 200, replayed=true, same transaction, no new charge")
    void replayIsIdempotent() {
        ResponseEntity<Map> res = http.postForEntity("/v1/usage", Map.of(
            "eventId", "evt-1", "tenantId", TENANT, "metric", "agent_seconds", "quantity", 120), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("replayed", true);
        assertThat(UUID.fromString((String) res.getBody().get("transactionId"))).isEqualTo(firstTx);
    }

    @Test @Order(3)
    @DisplayName("GET /v1/tenants/{id}/balance shows exactly what was charged, once")
    void balanceReflectsOneCharge() {
        ResponseEntity<Map> res = http.getForEntity("/v1/tenants/" + TENANT + "/balance", Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("owedMinor", 240).containsEntry("display", "2.40 GBP");
    }

    @Test @Order(4)
    @DisplayName("an unknown metric is 422 problem+json, and charges nothing")
    void unknownMetricIsRejected() {
        ResponseEntity<Map> res = http.postForEntity("/v1/usage", Map.of(
            "eventId", "evt-2", "tenantId", TENANT, "metric", "moon_landings", "quantity", 1), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(res.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat(res.getBody()).containsEntry("title", "Unknown metric");

        Map balance = http.getForObject("/v1/tenants/" + TENANT + "/balance", Map.class);
        assertThat(balance).containsEntry("owedMinor", 240);
    }

    @Test @Order(5)
    @DisplayName("a malformed request is 400 and names the field")
    void validationIsExplicit() {
        ResponseEntity<Map> res = http.postForEntity("/v1/usage", Map.of(
            "eventId", "", "tenantId", TENANT, "metric", "seats", "quantity", 0), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) res.getBody().get("detail")).contains("eventId").contains("quantity");
    }

    @Test @Order(6)
    @DisplayName("POST /v1/ledger/{tx}/reverse zeroes the balance and keeps both lines in the ledger")
    void reversalIsVisible() {
        ResponseEntity<Map> res = http.postForEntity("/v1/ledger/" + firstTx + "/reverse", Map.of(
            "tenantId", TENANT, "idempotencyKey", "rev-1", "reason", "goodwill credit"), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map balance = http.getForObject("/v1/tenants/" + TENANT + "/balance", Map.class);
        assertThat(balance).containsEntry("owedMinor", 0);

        List lines = http.getForObject("/v1/tenants/" + TENANT + "/ledger", List.class);
        assertThat(lines).hasSize(4);   // charge: 2 postings, reversal: 2 postings. Nothing deleted.
    }

    @Test @Order(7)
    @DisplayName("reversing the same transaction twice is 409, not a second reversal")
    void doubleReversalIsConflict() {
        ResponseEntity<Map> res = http.postForEntity("/v1/ledger/" + firstTx + "/reverse", Map.of(
            "tenantId", TENANT, "idempotencyKey", "rev-2", "reason", "again"), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).containsEntry("title", "Ledger refused the operation");
    }
}
