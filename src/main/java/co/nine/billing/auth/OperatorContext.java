package co.nine.billing.auth;

/**
 * Cross-tenant context for the reconciliation job only. Never set from an
 * HTTP request: there is no endpoint, header or parameter that reaches this.
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
