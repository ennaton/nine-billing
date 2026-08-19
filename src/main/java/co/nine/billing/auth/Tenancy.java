package co.nine.billing.auth;

import java.util.UUID;

/** One-line guard for controllers: the tenant in the request must be the tenant of the key. */
public final class Tenancy {
    private Tenancy() {}

    public static UUID requireOwn(UUID requested) {
        UUID own = TenantContext.require();
        if (!own.equals(requested)) throw new TenantMismatchException();
        return own;
    }
}
