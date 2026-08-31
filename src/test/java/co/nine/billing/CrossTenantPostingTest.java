package co.nine.billing;

import co.nine.billing.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BI12.3. A posting lands on an account, and the account belongs to a tenant.
 *
 * <p>{@code postings} carries no {@code tenant_id}; it inherits one through its
 * transaction, and the row-level security policy checks exactly that and only
 * that. Nothing checks the other end. A tenant may therefore write a posting on
 * a transaction it owns onto an account it does not own, and no constraint,
 * trigger or foreign key in the schema refuses it: the composite foreign key
 * binds the posting's currency to the account, not its tenant.
 *
 * <p>The two postings go in as one statement on purpose. The balance check is a
 * deferred constraint trigger, so two separate statements would each autocommit
 * and the first would be refused for being unbalanced, which is a different
 * refusal and would make this test pass for the wrong reason.
 *
 * <p>Ownership is read back through a superuser connection, outside RLS. Asking
 * the writer whether its own write landed is asking the wrong party: {@code B}
 * cannot see rows on {@code A}'s account whether they exist or not.
 */
@SpringBootTest
class CrossTenantPostingTest extends PostgresTestBase {

    @Autowired JdbcTemplate jdbc;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID accountA = UUID.randomUUID();
    private final UUID accountB = UUID.randomUUID();

    @BeforeEach
    void twoTenantsWithAnAccountEach() {
        open(tenantA, accountA);
        open(tenantB, accountB);
    }

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a tenant cannot post onto another tenant's account")
    void aTenantCannotPostOntoAnotherTenantsAccount() {
        TenantContext.bind(tenantB);
        UUID tx = UUID.randomUUID();
        jdbc.update("INSERT INTO ledger_transactions (id, tenant_id, idempotency_key, description, occurred_at)"
            + " VALUES (?, ?, ?, ?, now())", tx, tenantB, "cross-tenant-" + tx, "B's own transaction");

        // Balanced, and in the currency both accounts hold, so the only rule left
        // to refuse it is the one that says an account belongs to a tenant.
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO postings (transaction_id, account_id, direction, amount_minor, currency)"
                + " VALUES (?, ?, 'DEBIT', 100, 'GBP'), (?, ?, 'CREDIT', 100, 'GBP')",
            tx, accountA, tx, accountB))
            .as("B owns the transaction but not the account the debit lands on")
            .rootCause().hasMessageContaining("row-level security");

        assertThat(postingsOn(accountA))
            .as("nothing another tenant wrote may sit on this account")
            .isZero();
    }

    private void open(UUID tenant, UUID account) {
        TenantContext.bind(tenant);
        try {
            jdbc.update("INSERT INTO accounts (id, tenant_id, code, type, currency)"
                + " VALUES (?, ?, 'receivable', 'ASSET', 'GBP')", account, tenant);
        } finally {
            TenantContext.clear();
        }
    }

    /** Outside the pool and outside RLS: the only party that can see both books. */
    private long postingsOn(UUID account) {
        return new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
            .queryForObject("SELECT count(*) FROM postings WHERE account_id = ?", Long.class, account);
    }
}
