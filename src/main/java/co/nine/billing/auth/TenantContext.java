package co.nine.billing.auth;

import java.util.Optional;
import java.util.UUID;

/**
 * The tenant of the current request, bound to the thread by the auth filter
 * and read by the database layer when it opens a transaction.
 *
 * <p>Kept deliberately small: one value, one thread, cleared at the end of the
 * request. If it is ever found set outside a request, that is a bug.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void bind(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        UUID t = CURRENT.get();
        if (t == null) throw new IllegalStateException("no tenant bound to this request");
        return t;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
