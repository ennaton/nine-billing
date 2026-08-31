package co.nine.billing;

import co.nine.billing.auth.TenantAwareDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one part of the tenant boundary that a real Postgres cannot be asked to
 * exercise: what happens to a pooled connection when binding the tenant fails.
 *
 * <p>Every other test in this suite runs against a real database on purpose,
 * because the invariants live in triggers and policies. This one does not,
 * because the case under test is a database that has just stopped answering,
 * and there is no way to make a healthy Postgres throw on a prepare at the
 * exact moment the wrapper needs it to.
 *
 * <p>Why it matters: the connection is taken from the pool before the GUC is
 * set. A connection that is never closed is never returned, and Hikari does not
 * reclaim one it still believes is in use. So a failure here does not degrade
 * the service, it removes one connection from a pool of ten, permanently, once
 * per occurrence. A few seconds of a database failover empties the pool and the
 * service does not recover without a restart.
 */
class TenantAwareDataSourceTest {

    @Test
    @DisplayName("a connection is returned to the pool when the prepare fails")
    void prepareFailureDoesNotLeakTheConnection() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("connection is closed"));

        DataSource target = mock(DataSource.class);
        when(target.getConnection()).thenReturn(connection);

        TenantAwareDataSource wrapper = new TenantAwareDataSource(target, TenantAwareDataSourceTest::noOperatorPoolHere);

        assertThatThrownBy(wrapper::getConnection).isInstanceOf(SQLException.class);
        verify(connection, times(1)).close();
    }

    @Test
    @DisplayName("a connection is returned to the pool when the statement fails")
    void executeFailureDoesNotLeakTheConnection() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);
        when(statement.execute()).thenThrow(new SQLException("terminating connection due to administrator command"));

        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        DataSource target = mock(DataSource.class);
        when(target.getConnection()).thenReturn(connection);

        TenantAwareDataSource wrapper = new TenantAwareDataSource(target, TenantAwareDataSourceTest::noOperatorPoolHere);

        assertThatThrownBy(wrapper::getConnection).isInstanceOf(SQLException.class);
        verify(connection, times(1)).close();
    }

    @Test
    @DisplayName("a healthy connection is handed on and is not closed")
    void healthyConnectionIsNotClosed() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);

        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        DataSource target = mock(DataSource.class);
        when(target.getConnection()).thenReturn(connection);

        TenantAwareDataSource wrapper = new TenantAwareDataSource(target, TenantAwareDataSourceTest::noOperatorPoolHere);

        assertThat(wrapper.getConnection()).isSameAs(connection);
        verify(connection, never()).close();
    }
    /**
     * These three cases are about the tenant path, so the operator pool must
     * never be reached. A supplier that throws says so out loud: if routing
     * ever sends an ordinary checkout down the operator branch, the test fails
     * here rather than passing for the wrong reason.
     */
    private static javax.sql.DataSource noOperatorPoolHere() {
        throw new AssertionError("the tenant path must not reach the operator pool");
    }
}
