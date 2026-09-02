package co.nine.billing.domain;

import java.util.Currency;

/**
 * The gate a client supplied currency code passes through before anything else
 * sees it.
 *
 * <p>{@code Money.of} already refuses an unknown code, but it refuses it deep
 * inside a read, with a plain {@link IllegalArgumentException} that the error
 * contract cannot tell apart from a domain guard. So the balance endpoint
 * answered {@code ?currency=AAA} with 422, which says the request was understood
 * and the state refused it. Nothing about the state was involved.
 *
 * <p>The line this class draws: 400 when the caller could have known without
 * asking us, 422 when only our data decides. ISO 4217 is a closed published set,
 * so a code outside it is the first kind. An unknown metric stays the second
 * kind, because the set of metrics is our price table.
 */
public final class Currencies {

    /** How much of a rejected code is quoted back. Long enough to be useful. */
    private static final int QUOTED = 16;

    private Currencies() {}

    /**
     * The code, unchanged, if it names a currency. Throws if it does not.
     *
     * <p>Returns the code rather than a {@code Currency} so callers keep the
     * string they were given, which is what they report back to the client.
     */
    public static String require(String code) {
        if (code == null || code.isBlank()) {
            throw new UnknownCurrencyException("a currency code is required");
        }
        try {
            Currency.getInstance(code);
            return code;
        } catch (IllegalArgumentException notACurrency) {
            // The code is quoted back so the caller can see what we read, and
            // truncated because it arrives from the query string and nothing
            // upstream bounds its length.
            throw new UnknownCurrencyException(
                Quoted.value(code, QUOTED) + " is not an ISO 4217 currency code");
        }
    }
}
