package co.nine.billing.domain;

/**
 * A currency code that is not in ISO 4217.
 *
 * <p>Extends {@link IllegalArgumentException} because that is what it is, and
 * because {@code Money} has always thrown that for the same input. The reason it
 * is a type of its own is the status code: a caller who names a currency that
 * does not exist has sent a malformed request, not a request the ledger's state
 * happened to refuse, and only a named exception can carry that distinction to
 * the handler.
 */
public class UnknownCurrencyException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public UnknownCurrencyException(String message) {
        super(message);
    }
}
