package co.nine.billing.auth;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Sets the tenant GUC on every connection handed to the application, and hands
 * operator work a connection under a different database role.
 *
 * <p>This is the answer to "the helper nobody calls". The tenant context is not
 * something a repository remembers to apply; it is applied the moment a
 * connection is obtained, for every statement, with no opt-in. A query that
 * runs outside a request (no tenant bound) gets an empty GUC and the policies
 * return zero rows: fail-closed.
 *
 * <p>{@code set_config(..., false)} is session-scoped on purpose. Pooled
 * connections are reused across requests, so the value is overwritten on every
 * checkout rather than relied upon to reset.
 *
 * <p><strong>Operator is a role, not a GUC.</strong> It used to be the second
 * half of the same {@code set_config} call, which meant the runtime role
 * decided for itself whether it was an operator: one statement turned a read of
 * {@code reconciliation_findings} from zero rows into all of them, from a role
 * holding no privilege that could produce it. Operator work now arrives on a
 * connection authenticated as {@code nine_operator}, and {@code is_operator()}
 * asks {@code current_user}. See V9 and
 * {@code docs/artifacts/2026-08-31-operator-is-not-a-guc.md}.
 *
 * <p>The routing decision is taken at checkout, which is the only moment it can
 * be taken: Spring acquires the connection when a transaction opens, so a
 * caller that enters {@link OperatorContext} <em>inside</em> an already open
 * transaction keeps the connection the transaction is already bound to and
 * silently runs as {@code nine_app}. Every caller today wraps the transaction
 * rather than sitting inside one, and a new one has to do the same.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    /**
     * Resolved on first operator use rather than at construction. This class is
     * built by a BeanPostProcessor, which runs before Flyway, and V9 is what
     * creates the role the operator pool authenticates as.
     */
    private final Supplier<DataSource> operator;

    public TenantAwareDataSource(DataSource target, Supplier<DataSource> operator) {
        super(target);
        this.operator = operator;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (OperatorContext.isActive()) {
            return operator.get().getConnection();
        }
        return bind(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        if (OperatorContext.isActive()) {
            return operator.get().getConnection();
        }
        return bind(super.getConnection(username, password));
    }

    /**
     * Binds the tenant, and hands the connection back to the pool if it cannot.
     *
     * <p>The connection is already checked out by the time this runs, so an
     * exception here is not just a failed request. A connection that is never
     * closed is never returned, and the pool does not reclaim one it still
     * believes is in use, so each failure removes one connection permanently.
     * A database failover fails every checkout in the same way and empties the
     * pool, and the service does not recover without a restart. Closing on the
     * way out turns that into an ordinary error.
     */
    private static Connection bind(Connection c) throws SQLException {
        try {
            UUID tenant = TenantContext.current().orElse(null);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT set_config('app.tenant_id', ?, false)")) {
                ps.setString(1, tenant == null ? "" : tenant.toString());
                ps.execute();
            }
            return c;
        } catch (SQLException | RuntimeException e) {
            try {
                c.close();
            } catch (SQLException closeFailure) {
                // The original failure is the one worth reporting. Keep the
                // close failure attached rather than letting it replace it.
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }
}
