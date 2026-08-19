package co.nine.billing.auth;

/** The request names a tenant that is not the caller's. Surfaces as 404, never 403: existence is not disclosed. */
public class TenantMismatchException extends RuntimeException {
    public TenantMismatchException() {
        super("not found");
    }
}
