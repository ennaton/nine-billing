package co.nine.billing.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Mints API keys. Lives outside /v1 so the tenant filter does not apply, and
 * is guarded by a bootstrap secret from the environment instead: this is how
 * the very first key for a tenant comes to exist. In production this endpoint
 * is for an operator, not for tenants.
 *
 * <p>The plaintext key is returned once and never again. Store it.
 */
@RestController
@RequestMapping("/admin/keys")
public class KeyBootstrapController {

    private final ApiKeyRepository keys;
    private final String bootstrapSecret;

    public KeyBootstrapController(ApiKeyRepository keys,
                                  @Value("${nine.billing.bootstrap-secret:}") String bootstrapSecret) {
        this.keys = keys;
        this.bootstrapSecret = bootstrapSecret;
    }

    public record MintRequest(@NotNull UUID tenantId, @NotBlank String label) {}
    public record MintResponse(UUID tenantId, String label, String apiKey, String note) {}

    @PostMapping
    public ResponseEntity<?> mint(@RequestHeader(value = "X-Bootstrap-Secret", required = false) String secret,
                                  @Valid @RequestBody MintRequest r) {
        if (bootstrapSecret.isBlank() || secret == null || !constantTimeEquals(secret, bootstrapSecret)) {
            ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "missing or invalid X-Bootstrap-Secret");
            p.setTitle("Unauthorized");
            return ResponseEntity.status(401).body(p);
        }
        String plaintext = OperatorContext.run(() -> keys.create(r.tenantId(), r.label()));
        return ResponseEntity.status(HttpStatus.CREATED).body(
            new MintResponse(r.tenantId(), r.label(), plaintext, "shown once; it is not stored and cannot be recovered"));
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(), y = b.getBytes();
        if (x.length != y.length) return false;
        int d = 0;
        for (int i = 0; i < x.length; i++) d |= x[i] ^ y[i];
        return d == 0;
    }
}
