package co.nine.billing.reconciliation;

import co.nine.billing.auth.OperatorContext;
import co.nine.billing.reconciliation.ReconciliationRepository.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Compares what metering says it charged with what the ledger holds, on a
 * schedule and on demand. A clean run is recorded too: "we checked and found
 * nothing" is evidence, silence is not.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationRepository repo;

    /**
     * An explicit TransactionTemplate rather than {@code @Transactional} on a
     * method this class calls itself: a self invocation does not pass through
     * the proxy, so the annotation would be silently ignored. Writing the
     * boundary out also makes the ordering visible, and the ordering is the
     * whole point here.
     */
    private final TransactionTemplate tx;

    public ReconciliationService(ReconciliationRepository repo, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
    }

    public record Report(long runId, long chargesChecked, List<Finding> findings, boolean clean) {}

    /** Every 15 minutes by default; tunable per environment. */
    @Scheduled(fixedDelayString = "${nine.billing.reconcile.interval:PT15M}")
    public void scheduled() {
        Report r = run();
        if (r.clean()) {
            log.info("reconciliation clean: {} charges checked (run {})", r.chargesChecked(), r.runId());
        } else {
            log.warn("reconciliation found {} drift(s) across {} charges (run {})",
                r.findings().size(), r.chargesChecked(), r.runId());
        }
    }

    /**
     * Operator context is established <em>outside</em> the transaction, and the
     * transactional work is a separate call.
     *
     * <p>The previous shape had {@code @Transactional} on this method and set
     * operator context in its body. That is too late: Spring acquires the
     * connection when the transaction opens, and {@code TenantAwareDataSource}
     * writes the GUCs at that moment, before the body runs. The job therefore
     * ran with whatever tenant happened to be bound, and with none bound it saw
     * zero rows and reported "clean" forever, which is precisely the silent
     * failure this design exists to prevent.
     *
     * <p>A test that binds no tenant, tampers with the ledger and expects a
     * finding is what catches this. The earlier test passed only because it
     * left a tenant bound, so it was measuring tenant context, not operator.
     */
    public Report run() {
        return OperatorContext.run(() -> tx.execute(status -> compare()));
    }

    private Report compare() {
        Instant started = Instant.now();

        List<Finding> mismatches = repo.amountMismatches();
        List<Finding> orphans    = repo.orphanCharges();
        List<Finding> unbalanced = repo.unbalancedTransactions();
        long checked = repo.chargesCount();

        List<Finding> all = new ArrayList<>(mismatches.size() + orphans.size() + unbalanced.size());
        all.addAll(mismatches); all.addAll(orphans); all.addAll(unbalanced);

        long runId = repo.recordRun(started, Instant.now(), checked,
            mismatches.size(), orphans.size(), unbalanced.size(), all);

        return new Report(runId, checked, all, all.isEmpty());
    }
}
