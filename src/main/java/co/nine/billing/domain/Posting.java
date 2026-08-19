package co.nine.billing.domain;

import java.util.Objects;
import java.util.UUID;

/** One side of one transaction. Amount is always positive; direction carries the sign. */
public record Posting(UUID accountId, Direction direction, Money amount) {

    public Posting {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("posting amount must be positive, got " + amount);
        }
    }

    public static Posting debit(UUID accountId, Money amount) {
        return new Posting(accountId, Direction.DEBIT, amount);
    }

    public static Posting credit(UUID accountId, Money amount) {
        return new Posting(accountId, Direction.CREDIT, amount);
    }
}
