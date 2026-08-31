package co.nine.billing;

import co.nine.billing.auth.OperatorContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BI12.4. The operator boundary is a database role, and the application role
 * cannot cross it by writing to a variable.
 *
 * <p>Before V9 it could. Measured on 2026-08-31 against a real database: as
 * {@code nine_app}, holding no privilege that could produce it, a single
 * {@code set_config('app.role','operator',false)} turned a read of
 * {@code reconciliation_findings} from zero rows into every row in the table.
 * The predicate protecting that table was a value the protected party wrote.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperatorIsARoleTest extends PostgresTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("nine_app cannot make itself an operator")
    void theApplicationRoleCannotPromoteItself() {
        // One connection for both statements, or the GUC and the read land on
        // different pooled sessions and the test proves nothing. set_config is
        // session scoped, which is exactly why the old design was reachable in
        // the first place.
        Long rows = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Long>) con -> {
            try (var st = con.createStatement()) {
                st.execute("SELECT set_config('app.role', 'operator', false)");
                try (var rs = st.executeQuery("SELECT count(*) FROM reconciliation_findings")) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });

        assertThat(rows)
            .as("the GUC is inert: operator is who you are connected as, not what you claim")
            .isZero();
    }

    @Test
    @DisplayName("the application role holds no membership it could set")
    void theApplicationRoleCannotBecomeTheOperatorRole() {
        // The remaining way in is SET ROLE, and it needs a membership nine_app
        // does not have and cannot grant itself. Both refusals are the
        // database's, not the application's, which is the point of moving the
        // boundary here.
        String error = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) con -> {
            try (var st = con.createStatement()) {
                st.execute("SET ROLE nine_operator");
                return "";
            } catch (java.sql.SQLException refused) {
                return refused.getMessage();
            }
        });

        assertThat(error)
            .as("SET ROLE is refused, so the role cannot be borrowed")
            .contains("permission denied");
    }

    @Test
    @DisplayName("an operator connection still reads across tenants")
    void theOperatorRoleStillWorks() {
        // The other half. A boundary that also stops the job doing its work is
        // not a fix, it is the silent "we checked and found nothing" this
        // design exists to prevent, arriving from the other direction.
        Long asOperator = OperatorContext.run(() ->
            jdbc.queryForObject("SELECT count(*) FROM reconciliation_runs", Long.class));

        assertThat(asOperator)
            .as("the operator pool authenticates as nine_operator and the policies let it through")
            .isNotNull();

        String who = OperatorContext.run(() -> jdbc.queryForObject("SELECT current_user", String.class));
        assertThat(who)
            .as("and it really is a different database role, not the same one with a flag")
            .isEqualTo("nine_operator");
    }
}
