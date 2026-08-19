package co.nine.billing.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A balanced set of postings with the caller's idempotency key.
 *
 * <p>Balance is checked here in Java <em>and</em> by the database. Java gives
 * the caller a clear error before a round trip; the database is the guarantee
 * that holds even when someone bypasses this class. Both, deliberately.
 */
public record LedgerEntry(
        UUID tenantId,
        String idempotencyKey,
        String description,
        Instant occurredAt,
        List<Posting> postings,
        Optional<UUID> reversesTransactionId) {

    public LedgerEntry {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(reversesTransactionId, "reversesTransactionId");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        postings = List.copyOf(postings);
        if (postings.size() < 2) {
            throw new IllegalArgumentException("a ledger entry needs at least two postings");
        }
        requireSingleCurrency(postings);
        requireBalanced(postings);
    }

    public Currency currency() {
        return postings.get(0).amount().currency();
    }

    private static void requireSingleCurrency(List<Posting> postings) {
        Currency first = postings.get(0).amount().currency();
        for (Posting p : postings) {
            if (!p.amount().currency().equals(first)) {
                throw new IllegalArgumentException(
                    "all postings in one entry must share a currency: " + first + " vs " + p.amount().currency());
            }
        }
    }

    private static void requireBalanced(List<Posting> postings) {
        long debits = 0, credits = 0;
        for (Posting p : postings) {
            if (p.direction() == Direction.DEBIT) debits = Math.addExact(debits, p.amount().minor());
            else credits = Math.addExact(credits, p.amount().minor());
        }
        if (debits != credits) {
            throw new UnbalancedEntryException(debits, credits);
        }
    }
}
