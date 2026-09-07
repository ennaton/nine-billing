package co.nine.billing.reconciliation;

import co.nine.billing.reconciliation.ReconciliationRepository.RunSummary;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The newest run, and nothing else. /actuator is served without a key, so this
 * adds one term to a status an anonymous caller already gets: whether the last
 * reconciliation came back clean. It cannot be read on its own, because the
 * root status is the aggregate of every indicator. The counts and the reason
 * stay in reconciliation_findings, which only the operator role reads.
 */
@Component
public class ReconciliationHealth implements HealthIndicator {

    private final ReconciliationRepository repo;

    public ReconciliationHealth(ReconciliationRepository repo) {
        this.repo = repo;
    }

    @Override
    public Health health() {
        List<RunSummary> recent;
        try {
            recent = repo.recentRuns(1);
        } catch (RuntimeException cannotTell) {
            // HealthIndicator has no wrapper that turns a throw into DOWN, so an
            // exception here leaves the endpoint answering 500 with an error page
            // instead of a health document. db already reports the database, this
            // only has to avoid being the one that breaks the answer.
            return Health.down().build();
        }
        // A service that has not reconciled yet is not a broken one. How long
        // since the last run is a different signal and it is not free: it would
        // tell anyone who asks how long the job has been down.
        if (recent.isEmpty()) return Health.up().build();
        return recent.get(0).clean() ? Health.up().build() : Health.down().build();
    }
}
