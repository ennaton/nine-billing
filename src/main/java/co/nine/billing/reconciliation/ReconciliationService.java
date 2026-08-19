package co.nine.billing.reconciliation;

import co.nine.billing.reconciliation.ReconciliationRepository.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public ReconciliationService(ReconciliationRepository repo) {
        this.repo = repo;
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

    @Transactional
    public Report run() {
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
