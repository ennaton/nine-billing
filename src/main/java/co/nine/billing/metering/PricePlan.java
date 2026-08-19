package co.nine.billing.metering;

import co.nine.billing.domain.Money;

/** Price of one unit of a metric, in minor units of a single currency. */
public record PricePlan(String metric, long unitPriceMinor, String currency, String description) {

    /**
     * Total for a quantity. Overflow is an error, not a wrap: a quantity large
     * enough to overflow a long is a bug upstream, and silently charging the
     * wrong number is the one thing a billing system must never do.
     */
    public Money priceFor(long quantity) {
        return Money.of(Math.multiplyExact(unitPriceMinor, quantity), currency);
    }
}
