package co.nine.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BI15.1. The problem+json contract covers what this service throws. These are
 * the errors Spring throws before a handler runs, which the contract said
 * nothing about.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class FrameworkErrorsTest extends PostgresTestBase {

    static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;
    static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired TestRestTemplate raw;

    TestRestTemplate authed() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Bootstrap-Secret", "test-bootstrap-secret");
        ResponseEntity<Map> res = raw.postForEntity("/admin/keys",
            new HttpEntity<>(Map.of("tenantId", TENANT, "label", "framework-errors"), h), Map.class);
        String key = (String) res.getBody().get("apiKey");
        return new TestRestTemplate(new RestTemplateBuilder()
            .baseUri(raw.getRootUri()).defaultHeader("X-Api-Key", key));
    }

    ResponseEntity<String> send(HttpMethod method, String path, MediaType type, String body) {
        HttpHeaders h = new HttpHeaders();
        if (type != null) h.setContentType(type);
        return authed().exchange(path, method, new HttpEntity<>(body, h), String.class);
    }

    @Test
    @DisplayName("a method the route does not serve is problem+json")
    void methodNotAllowed() {
        ResponseEntity<String> res = send(HttpMethod.DELETE, "/v1/usage", null, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.getHeaders().getContentType()).isNotNull();
        assertThat(res.getHeaders().getContentType().isCompatibleWith(PROBLEM)).isTrue();
    }

    @Test
    @DisplayName("a body that does not parse is problem+json")
    void unreadableBody() {
        ResponseEntity<String> res = send(HttpMethod.POST, "/v1/usage", MediaType.APPLICATION_JSON, "{");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getHeaders().getContentType().isCompatibleWith(PROBLEM)).isTrue();
    }

    @Test
    @DisplayName("a content type the route does not accept is problem+json")
    void unsupportedMediaType() {
        ResponseEntity<String> res = send(HttpMethod.POST, "/v1/usage", MediaType.TEXT_PLAIN, "x");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(res.getHeaders().getContentType().isCompatibleWith(PROBLEM)).isTrue();
    }

    @Test
    @DisplayName("a route that does not exist is problem+json")
    void noSuchRoute() {
        ResponseEntity<String> res = send(HttpMethod.GET, "/v1/nope", null, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getHeaders().getContentType().isCompatibleWith(PROBLEM)).isTrue();
    }

    @Test
    @DisplayName("an unparseable path variable is 400 and does not echo the framework's message")
    void badPathVariable() {
        ResponseEntity<String> res = send(HttpMethod.GET, "/v1/tenants/not-a-uuid/balance", null, null);
        // 422 means well formed but cannot be honored. A path that is not a UUID
        // is not well formed, and the caller does not need our conversion types.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getHeaders().getContentType().isCompatibleWith(PROBLEM)).isTrue();
        assertThat(res.getBody()).doesNotContain("java.util.UUID");
    }
}
