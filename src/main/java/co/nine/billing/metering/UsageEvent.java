package co.nine.billing.metering;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One unit of metered usage as reported by an upstream service.
 *
 * <p>{@code eventId} is the idempotency key. The same event reported twice is
 * charged once; the ledger's unique constraint guarantees it, this record only
 * carries the key through.
 */
public record UsageEvent(String eventId, UUID tenantId, String metric, long quantity, Instant occurredAt) {

    public UsageEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("eventId is required");
        if (metric == null || metric.isBlank()) throw new IllegalArgumentException("metric is required");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive, got " + quantity);
    }
}
