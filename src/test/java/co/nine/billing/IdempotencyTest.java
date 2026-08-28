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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The idempotency contract, driven the two ways a replay actually reaches the
 * ledger.
 *
 * <p>The README states that a replayed key returns the original transaction, and
 * that the guarantee rests on a unique constraint rather than on an application
 * level "does this key exist" check, because that check races. Both halves are
 * tested here: the check in front is not what makes replay work, and the
 * constraint behind it has to answer correctly on its own.
 *
 * <p>Each test mints its own tenant, so nothing here depends on ordering or on
 * rows another test left behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class IdempotencyTest extends PostgresTestBase {

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
            new HttpEntity<>(Map.of("tenantId", tenant, "label", "idempotency"), headers), JSON);
        http = new TestRestTemplate(new RestTemplateBuilder()
            .baseUri(raw.getRootUri())
            .defaultHeader("X-Api-Key", (String) minted.getBody().get("apiKey")));
    }

    private ResponseEntity<Map<String, Object>> usage(String eventId, int quantity) {
        return http.exchange("/v1/usage", HttpMethod.POST,
            new HttpEntity<>(Map.of("eventId", eventId, "tenantId", tenant,
                "metric", "agent_seconds", "quantity", quantity)), JSON);
    }

    @Test
    @DisplayName("the constraint answers a replay itself, without the check in front of it")
    void constraintPathReturnsTheOriginalRatherThanFailing() {
        // Reach the constraint deliberately, with no concurrency involved. The
        // reversal idempotency key is chosen by the caller, and metering builds
        // its own as "usage:" + eventId. Spending that string on a reversal
        // leaves a ledger transaction holding the key with no usage_charges row
        // behind it, so the check in MeteringService misses and the insert goes
        // to the constraint. That is the path the README says the guarantee
        // rests on.
        ResponseEntity<Map<String, Object>> seed = usage("seed", 10);
        assertThat(seed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID seedTx = UUID.fromString((String) seed.getBody().get("transactionId"));

        ResponseEntity<Map<String, Object>> reversal = http.exchange(
            "/v1/ledger/" + seedTx + "/reverse", HttpMethod.POST,
            new HttpEntity<>(Map.of("tenantId", tenant,
                "idempotencyKey", "usage:collide", "reason", "spend the key")), JSON);
        assertThat(reversal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Object reversalTx = reversal.getBody().get("reversalTransactionId");

        ResponseEntity<Map<String, Object>> collided = usage("collide", 10);

        // The key is taken, so the insert conflicts. What matters here is that the
        // conflict resolves to the transaction already holding the key instead of
        // aborting the transaction the lookup would have to run in. Before this
        // change the same call answered 500, first from 25P02 and then, once the
        // conflict was suppressed but still raised across the transactional
        // boundary, from UnexpectedRollbackException.
        assertThat(collided.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(collided.getBody()).containsEntry("transactionId", reversalTx);

        // Pinning what is still wrong rather than hiding it: the transaction the
        // caller is handed is a reversal, because metering and reversal share one
        // idempotency namespace. That is BI14.3, and this assertion is what will
        // fail when it is fixed, which is the point.
        assertThat(collided.getBody()).containsEntry("replayed", false);
    }

    @Test
    @DisplayName("fifty concurrent identical events charge once and every caller gets the same answer")
    void fiftyConcurrentIdenticalEventsAgree() {
        int callers = 50;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map<String, Object>>>> pending = IntStream.range(0, callers)
                .mapToObj(i -> pool.submit(() -> {
                    start.await();
                    return usage("race", 10);
                }))
                .toList();

            start.countDown();

            List<ResponseEntity<Map<String, Object>>> answers = pending.stream().map(f -> {
                try {
                    return f.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("a caller never answered", e);
                }
            }).toList();

            // Nobody is told the service broke. The ledger staying correct is not
            // enough on its own: a replay answered with a 500 is still a replay
            // the caller cannot act on.
            assertThat(answers).allSatisfy(answer ->
                assertThat(answer.getStatusCode().is5xxServerError())
                    .withFailMessage("a caller got %s", answer.getStatusCode())
                    .isFalse());

            // One charge, and every caller is pointed at it.
            assertThat(answers).extracting(answer -> answer.getBody().get("transactionId"))
                .containsOnly(answers.get(0).getBody().get("transactionId"));

            // Exactly one caller is told it created the charge.
            assertThat(answers).filteredOn(answer -> Boolean.FALSE.equals(answer.getBody().get("replayed")))
                .hasSize(1);

            ResponseEntity<Map<String, Object>> balance = http.exchange(
                "/v1/tenants/" + tenant + "/balance", HttpMethod.GET, null, JSON);
            assertThat(balance.getBody()).containsEntry("owedMinor", 20);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a replay reports what was charged, not what the request would cost now")
    void replayReportsTheStoredCharge() {
        ResponseEntity<Map<String, Object>> first = usage("price-drift", 10);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody()).containsEntry("chargedMinor", 20);

        // The same event, ten times the quantity. The event was charged once and
        // the ledger holds that number, so it is the number the caller is owed.
        // Answering with what this request would have cost describes a charge
        // that was never made, and reconciliation cannot see it because the rows
        // agree with each other: only the response is wrong.
        ResponseEntity<Map<String, Object>> replay = usage("price-drift", 100);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody())
            .containsEntry("replayed", true)
            .containsEntry("transactionId", first.getBody().get("transactionId"))
            .containsEntry("chargedMinor", 20)
            .containsEntry("currency", "GBP");
    }

    @Test
    @DisplayName("a replay does not depend on the metric still being priced")
    void replayDoesNotConsultCurrentPricing() {
        ResponseEntity<Map<String, Object>> first = usage("metric-drift", 10);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // The same eventId, carrying a metric that has no price plan at all. The
        // charge already happened; what the catalogue says today cannot unmake
        // it. A replay that has to price the request first is a replay that can
        // be refused by a change made after the fact.
        ResponseEntity<Map<String, Object>> replay = http.exchange("/v1/usage", HttpMethod.POST,
            new HttpEntity<>(Map.of("eventId", "metric-drift", "tenantId", tenant,
                "metric", "no_such_metric_at_all", "quantity", 10)), JSON);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody())
            .containsEntry("replayed", true)
            .containsEntry("transactionId", first.getBody().get("transactionId"))
            .containsEntry("chargedMinor", 20);
    }
}
