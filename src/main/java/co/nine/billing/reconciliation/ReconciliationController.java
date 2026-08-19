package co.nine.billing.reconciliation;

import co.nine.billing.reconciliation.ReconciliationRepository.Finding;
import co.nine.billing.reconciliation.ReconciliationRepository.RunSummary;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/reconciliation")
public class ReconciliationController {

    private final ReconciliationService service;
    private final ReconciliationRepository repo;

    public ReconciliationController(ReconciliationService service, ReconciliationRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    /** Run a reconciliation now and return its report. */
    @PostMapping("/run")
    public ReconciliationService.Report run() {
        return service.run();
    }

    /** Recent runs, newest first. `clean` tells you whether a run found anything. */
    @GetMapping("/runs")
    public List<RunSummary> runs(@RequestParam(defaultValue = "20") @Min(1) int limit) {
        return repo.recentRuns(Math.min(limit, 200));
    }

    /** Every drift found in one run. */
    @GetMapping("/runs/{runId}/findings")
    public List<Finding> findings(@PathVariable long runId) {
        return repo.findingsOf(runId);
    }
}
