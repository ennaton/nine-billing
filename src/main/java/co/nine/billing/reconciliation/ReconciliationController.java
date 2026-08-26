package co.nine.billing.reconciliation;

import co.nine.billing.reconciliation.ReconciliationRepository.RunSummary;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What a tenant may see of reconciliation: whether the last checks were clean.
 *
 * <p>Counts and a clean flag, never a finding. A finding names a tenant, an
 * event and an amount, so it is not tenant readable at any level: the row level
 * policy refuses it and there is no endpoint here that would ask.
 */
@RestController
@RequestMapping("/v1/reconciliation")
public class ReconciliationController {

    private final ReconciliationRepository repo;

    public ReconciliationController(ReconciliationRepository repo) {
        this.repo = repo;
    }

    /** Recent runs with their drift counts and a clean flag. No tenant identity here. */
    @GetMapping("/runs")
    public List<RunSummary> runs(@RequestParam(defaultValue = "20") @Min(1) int limit) {
        return repo.recentRuns(Math.min(limit, 200));
    }
}
