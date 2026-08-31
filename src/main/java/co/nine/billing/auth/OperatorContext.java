package co.nine.billing.auth;

/**
 * Cross-tenant context. Three callers, and two of them are HTTP.
 *
 * <p>This comment used to say "never set from an HTTP request: there is no
 * endpoint, header or parameter that reaches this". That was false, and it was
 * false in the one place a reader would go to decide whether the operator path
 * is safe. {@code POST /admin/keys} and
 * {@code GET /admin/reconciliation/runs/&#123;runId&#125;/findings} both reach
 * it, alongside the scheduled reconciliation job.
 *
 * <p>What is true, and what the sentence was reaching for: no header,
 * parameter or path a tenant controls turns this on. The two HTTP callers
 * enter it themselves after checking the bootstrap secret, which is a
 * constant time comparison in the application that fails closed on a blank
 * secret. That is a weaker guarantee than "unreachable", and since V9 it is
 * also a more consequential one, because the context now selects a connection
 * under a privileged database role rather than setting a variable. Whether an
 * HTTP path should be able to take that pool at all is a live question and
 * belongs with {@code /admin}'s place in {@code ApiKeyFilter}'s open list.
 *
 * <p>Enter it around a transaction, never inside one. Spring binds the
 * connection when the transaction opens, so a caller already in a transaction
 * keeps the connection it has and runs as {@code nine_app} without failing.
 */
public final class OperatorContext {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    private OperatorContext() {}

    public static boolean isActive() {
        return ACTIVE.get();
    }

    /** Run a block with operator context, and always clear it afterwards. */
    public static <T> T run(java.util.function.Supplier<T> block) {
        ACTIVE.set(true);
        try {
            return block.get();
        } finally {
            ACTIVE.remove();
        }
    }
}
