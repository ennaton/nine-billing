package co.nine.billing.reconciliation;

import co.nine.billing.auth.OperatorContext;
import co.nine.billing.reconciliation.ReconciliationRepository.Finding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Operator surface for reconciliation.
 *
 * <p>Outside {@code /v1} so no API key reaches it, and guarded by the same
 * bootstrap secret as key minting. Findings name tenants, so a tenant may not
 * read them and neither may a tenant's key: the caller here is an operator,
 * not a customer.
 *
 * <p>An earlier version exposed both of these under {@code /v1}, where any
 * valid key could read every tenant's findings. That is the bug this class
 * exists to close, and TenantIsolationTest keeps it closed.
 */
@RestController
@RequestMapping("/admin/reconciliation")
public class ReconciliationOperatorController {

    private final ReconciliationService service;
    private final ReconciliationRepository repo;
    private final String operatorSecret;

    public ReconciliationOperatorController(ReconciliationService service,
                                            ReconciliationRepository repo,
                                            @Value("${nine.billing.bootstrap-secret:}") String operatorSecret) {
        this.service = service;
        this.repo = repo;
        this.operatorSecret = operatorSecret;
    }

    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestHeader(value = "X-Bootstrap-Secret", required = false) String secret) {
        if (!authorised(secret)) return unauthorised();
        return ResponseEntity.ok(service.run());
    }

    @GetMapping("/runs/{runId}/findings")
    public ResponseEntity<?> findings(@RequestHeader(value = "X-Bootstrap-Secret", required = false) String secret,
                                      @PathVariable long runId) {
        if (!authorised(secret)) return unauthorised();
        List<Finding> found = OperatorContext.run(() -> repo.findingsOf(runId));
        return ResponseEntity.ok(found);
    }

    private boolean authorised(String secret) {
        return !operatorSecret.isBlank() && secret != null && constantTimeEquals(secret, operatorSecret);
    }

    private static ResponseEntity<ProblemDetail> unauthorised() {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "missing or invalid X-Bootstrap-Secret");
        p.setTitle("Unauthorized");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(p);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(), y = b.getBytes();
        if (x.length != y.length) return false;
        int d = 0;
        for (int i = 0; i < x.length; i++) d |= x[i] ^ y[i];
        return d == 0;
    }
}
