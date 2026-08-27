package co.nine.billing.auth;

/** The request names a tenant that is not the caller's. Surfaces as 404, never 403: existence is not disclosed. */
public class TenantMismatchException extends RuntimeException {
    // Never crosses a serialization boundary; the value only exists to keep
    // -Xlint:serial quiet and must not change if the class does.
    private static final long serialVersionUID = 1L;

    public TenantMismatchException() {
        super("not found");
    }
}
