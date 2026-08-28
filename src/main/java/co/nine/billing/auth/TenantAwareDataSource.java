package co.nine.billing.auth;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Sets the tenant GUC on every connection handed to the application.
 *
 * <p>This is the answer to "the helper nobody calls". The tenant context is not
 * something a repository remembers to apply; it is applied the moment a
 * connection is obtained, for every statement, with no opt-in. A query that
 * runs outside a request (no tenant bound) gets an empty GUC and the policies
 * return zero rows: fail-closed.
 *
 * <p>{@code set_config(..., false)} is session-scoped on purpose. Pooled
 * connections are reused across requests, so the value is overwritten on every
 * checkout rather than relied upon to reset. Operator context for the
 * reconciliation job goes through the same path with a different GUC.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bind(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
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
            String role = OperatorContext.isActive() ? "operator" : "";
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT set_config('app.tenant_id', ?, false), set_config('app.role', ?, false)")) {
                ps.setString(1, tenant == null ? "" : tenant.toString());
                ps.setString(2, role);
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
