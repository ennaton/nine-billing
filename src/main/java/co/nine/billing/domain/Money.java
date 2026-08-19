package co.nine.billing.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * An amount in a currency's minor units (pence, cents). Never a floating type.
 *
 * <p>Two invariants live here so nothing else has to remember them: the amount
 * is a whole number of minor units, and two amounts only combine when their
 * currencies match. Arithmetic on mismatched currencies is a bug, not a
 * conversion, so it throws.
 */
public record Money(long minor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(long minor, String currencyCode) {
        return new Money(minor, Currency.getInstance(currencyCode));
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public boolean isPositive() {
        return minor > 0;
    }

    public boolean isZero() {
        return minor == 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                "currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return minor + " " + currency.getCurrencyCode();
    }
}
