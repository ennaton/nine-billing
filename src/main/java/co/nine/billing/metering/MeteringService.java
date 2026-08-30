package co.nine.billing.metering;

import co.nine.billing.application.LedgerService;
import co.nine.billing.domain.AccountType;
import co.nine.billing.domain.LedgerEntry;
import co.nine.billing.domain.Money;
import co.nine.billing.domain.Posting;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns usage into money.
 *
 * <p>One event becomes one ledger transaction: debit the tenant's receivable
 * (they owe us more), credit revenue (we earned it). The event id is the
 * idempotency key, so a retried event returns the original charge instead of
 * a second one.
 */
@Service
public class MeteringService {

    public static final String RECEIVABLE = "receivable";
    public static final String REVENUE = "revenue";

    private final MeteringRepository repo;
    private final LedgerService ledger;

    public MeteringService(MeteringRepository repo, LedgerService ledger) {
        this.repo = repo;
        this.ledger = ledger;
    }

    @Transactional
    public Charge charge(UsageEvent event) {
        // The replay check comes first, and deliberately before the price is
        // looked up. An event that has already been charged is answered from
        // what was recorded, so the answer cannot be moved by a later edit to
        // the catalogue and cannot be refused because the metric was since
        // withdrawn. Pricing is for charges that have not happened yet.
        Optional<MeteringRepository.RecordedCharge> already =
            repo.chargeFor(event.tenantId(), event.eventId());
        if (already.isPresent()) {
            return new Charge(already.get().transactionId(), already.get().amount(), true);
        }

        PricePlan plan = repo.pricePlan(event.metric())
            .orElseThrow(() -> new UnknownMetricException(event.metric()));
        Money amount = plan.priceFor(event.quantity());

        UUID receivable = repo.ensureAccount(event.tenantId(), RECEIVABLE, AccountType.ASSET, plan.currency());
        UUID revenue    = repo.ensureAccount(event.tenantId(), REVENUE,    AccountType.REVENUE, plan.currency());

        LedgerEntry entry = new LedgerEntry(
            event.tenantId(),
            "usage:" + event.eventId(),
            event.metric() + " x" + event.quantity(),
            event.occurredAt(),
            List.of(Posting.debit(receivable, amount), Posting.credit(revenue, amount)),
            Optional.empty());

        UUID txId = ledger.post(entry);

        // The check above can lose a race, so whether this is a first charge is
        // decided by the write, not by the read that came before it.
        if (!repo.recordCharge(event, amount.minor(), plan.currency(), txId)) {
            // A concurrent caller wrote the charge first. Same rule as above:
            // the recorded amount is the answer, not the one computed here,
            // which is only equal to it while both callers agree on the
            // quantity.
            MeteringRepository.RecordedCharge winner =
                repo.chargeFor(event.tenantId(), event.eventId()).orElseThrow(
                    () -> new IllegalStateException(
                        "the insert conflicted but no charge is on record for " + event.eventId()));
            return new Charge(winner.transactionId(), winner.amount(), true);
        }
        return new Charge(txId, amount, false);
    }

    /**
     * How much the tenant currently owes: the receivable balance.
     *
     * <p>A read, and only a read. This used to call {@code ensureAccount}, so
     * asking for a balance created the account, from a GET, outside any
     * transaction. Since the account key is {@code (tenant, code)}, the currency
     * named by whichever read happened first became the tenant's currency for
     * good, every later posting failed against the composite foreign key, and no
     * grant existed that could undo it. A tenant that has never been charged owes
     * nothing, which is a fact this endpoint can report without writing it down.
     */
    public Money owed(UUID tenantId, String currency) {
        return repo.findAccount(tenantId, RECEIVABLE, currency)
            .map(receivable -> Money.of(ledger.balanceMinor(receivable), currency))
            .orElseGet(() -> Money.of(0, currency));
    }

    public List<MeteringRepository.LedgerLine> recent(UUID tenantId, int limit) {
        return repo.recentLines(tenantId, limit);
    }
}
