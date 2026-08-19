package co.nine.billing.application;

import co.nine.billing.domain.Direction;
import co.nine.billing.domain.DuplicateEntryException;
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

    private final LedgerRepository repo;

    public LedgerService(LedgerRepository repo) {
        this.repo = repo;
    }

    /**
     * Idempotent post. A replay of the same (tenant, key) returns the original
     * transaction id instead of failing, so a client that retries after a
     * network timeout gets the same answer it would have got the first time.
     */
    public UUID post(LedgerEntry entry) {
        try {
            return repo.record(entry);
        } catch (DuplicateEntryException e) {
            return repo.transactionIdFor(entry.tenantId(), entry.idempotencyKey());
        }
    }

    /**
     * The only sanctioned correction. Builds a mirror image of the original
     * transaction's postings (debits become credits and vice versa) and posts
     * it as a new transaction that points at the one it reverses. History is
     * never rewritten; the balance simply returns to where it was.
     */
    public UUID reverse(UUID tenantId, UUID originalTxId, String idempotencyKey, String reason) {
        List<Posting> mirrored = repo.postingsOf(originalTxId).stream()
            .map(p -> new Posting(
                p.accountId(),
                p.direction() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT,
                p.amount()))
            .toList();
        return post(new LedgerEntry(
            tenantId, idempotencyKey, reason, Instant.now(), mirrored, Optional.of(originalTxId)));
    }

    public long balanceMinor(UUID accountId) {
        return repo.balanceMinor(accountId);
    }
}
