package co.nine.billing.domain;

/** The (tenant, idempotency key) pair already exists. A retry, not an error. */
public class DuplicateEntryException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String idempotencyKey;

    public DuplicateEntryException(String idempotencyKey) {
        super("entry already recorded for idempotency key " + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
