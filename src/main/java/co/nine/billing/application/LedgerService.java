package co.nine.billing.application;

import co.nine.billing.domain.Direction;
import co.nine.billing.domain.LedgerEntry;
import co.nine.billing.domain.Posting;
import co.nine.billing.infrastructure.LedgerRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LedgerService {

    /**
     * Namespace for reversal idempotency keys.
     *
     * <p>A key is unique per {@code (tenant, key)} and nothing in the schema says
     * which operation wrote it, so every operation that writes one has to say so
     * itself. Metering names its keys {@code "usage:" + eventId}; a reversal key
     * is chosen by the caller, so this service names it rather than trusting the
     * caller to. Without it a caller could spend, by accident or otherwise, the
     * exact string metering will construct for an event that has not happened
     * yet, and that event's first charge would then be answered with the
     * reversal's transaction: a charge row pointing at something that reverses,
     * with no postings of its own.
     */
    static final String REVERSAL_KEY_PREFIX = "reversal:";

    private final LedgerRepository repo;

    public LedgerService(LedgerRepository repo) {
        this.repo = repo;
    }

    /**
     * Idempotent post. A replay of the same (tenant, key) returns the original
     * transaction id instead of failing, so a client that retries after a
     * network timeout gets the same answer it would have got the first time.
     *
     * <p>The replay is resolved inside the same statement that writes, so it
     * holds under concurrency as well as after a timeout. It used to be resolved
     * here, by catching the violation and reading the original afterwards, which
     * cannot work: by then Postgres has aborted the transaction the read would
     * have to run in.
     */
    public UUID post(LedgerEntry entry) {
        return repo.record(entry);
    }

    /**
     * The only sanctioned correction. Builds a mirror image of the original
     * transaction's postings (debits become credits and vice versa) and posts
     * it as a new transaction that points at the one it reverses. History is
     * never rewritten; the balance simply returns to where it was.
     */
    public UUID reverse(UUID tenantId, UUID originalTxId, String idempotencyKey, String reason) {
        List<Posting> original = repo.postingsOf(originalTxId);
        if (original.isEmpty()) {
            // Either the transaction does not exist or RLS hides it because it
            // belongs to another tenant. Same answer for both: not found.
            throw new org.springframework.dao.EmptyResultDataAccessException(
                "no such transaction for this tenant: " + originalTxId, 1);
        }
        List<Posting> mirrored = original.stream()
            .map(p -> new Posting(
                p.accountId(),
                p.direction() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT,
                p.amount()))
            .toList();
        return post(new LedgerEntry(
            tenantId, REVERSAL_KEY_PREFIX + idempotencyKey, reason, Instant.now(),
            mirrored, Optional.of(originalTxId)));
    }

    public long balanceMinor(UUID accountId) {
        return repo.balanceMinor(accountId);
    }
}
