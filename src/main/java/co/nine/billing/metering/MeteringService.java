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
        PricePlan plan = repo.pricePlan(event.metric())
            .orElseThrow(() -> new UnknownMetricException(event.metric()));
        Money amount = plan.priceFor(event.quantity());

        // Fast path for replays: the ledger would also refuse the duplicate
        // key, but checking here avoids creating postings we then discard.
        Optional<UUID> existing = repo.chargeFor(event.tenantId(), event.eventId());
        if (existing.isPresent()) {
            return new Charge(existing.get(), amount, true);
        }

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
        boolean recorded = repo.recordCharge(event, amount.minor(), plan.currency(), txId);
        return new Charge(txId, amount, !recorded);
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
        return repo.findAccount(tenantId, RECEIVABLE)
            .map(receivable -> Money.of(ledger.balanceMinor(receivable), currency))
            .orElseGet(() -> Money.of(0, currency));
    }

    public List<MeteringRepository.LedgerLine> recent(UUID tenantId, int limit) {
        return repo.recentLines(tenantId, limit);
    }
}
