package co.nine.billing.infrastructure;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The one place that says what a refused write means.
 *
 * <p>The ledger's guarantees live in constraints and triggers, so a caller meets
 * them by design rather than by accident. What used to reach them was the
 * Postgres message verbatim, which named an index, a table, sometimes a column
 * type. That made an index name part of the API: renaming one would have broken
 * a client, and the client had nothing better to key on because the rule itself
 * was never named.
 *
 * <p>Matching is on SQLState and the constraint name that the server reports as
 * a separate field, never on the message text. The text is formatting and it
 * changes between server versions; the pair below does not. Every row was
 * measured against a running Postgres 16 rather than read off a table.
 *
 * <p>Two rules carry no constraint name at all, because a {@code RAISE} inside a
 * trigger is not a constraint violation and Postgres has nothing to put in that
 * field. They are still unambiguous here: the balance check raises
 * {@code check_violation} and the immutability trigger raises
 * {@code restrict_violation}, so the SQLState alone separates them from each
 * other and, together with a null constraint, from the real constraints that
 * share a state.
 *
 * <p>Lives in infrastructure because the left half of the table is Postgres
 * vocabulary. The right half is the only part a caller ever sees.
 */
public final class ConstraintRules {

    private ConstraintRules() {}

    /** A rule the database enforces, and what breaking it means to a caller. */
    public enum Rule {

        DUPLICATE_IDEMPOTENCY_KEY("23505", "ledger_tx_tenant_idem_unique",
            "that idempotency key already belongs to another transaction"),

        ALREADY_REVERSED("23505", "ledger_tx_reverses_unique",
            "that transaction has already been reversed"),

        CURRENCY_MISMATCH("23503", "postings_currency_matches_account",
            "the amount is not in the account's currency"),

        AMOUNT_NOT_POSITIVE("23514", "postings_amount_minor_check",
            "a posting amount must be greater than zero"),

        /** Raised by {@code postings_balance_check}, which carries no constraint name. */
        DOES_NOT_BALANCE("23514", null,
            "the entry does not balance"),

        /** Raised by the immutability triggers, which carry no constraint name. */
        IMMUTABLE("23001", null,
            "ledger rows are immutable, post a reversing transaction instead");

        private final String sqlState;
        private final String constraint;
        private final String detail;

        Rule(String sqlState, String constraint, String detail) {
            this.sqlState = sqlState;
            this.constraint = constraint;
            this.detail = detail;
        }

        /** What the caller is told. Names the rule, never the object enforcing it. */
        public String detail() {
            return detail;
        }
    }

    /**
     * The rule behind a failure, if this is one the database refused on purpose.
     *
     * <p>Empty for anything else, and the caller of this method decides what to
     * say then. An unrecognised integrity failure is more likely to be a defect
     * in this service than a rule the client broke, and guessing a friendly
     * message for it would hide that.
     */
    public static Optional<Rule> of(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof PSQLException postgres) {
                ServerErrorMessage server = postgres.getServerErrorMessage();
                String constraint = server == null ? null : server.getConstraint();
                String state = postgres.getSQLState();
                return Arrays.stream(Rule.values())
                    .filter(rule -> rule.sqlState.equals(state)
                        && Objects.equals(rule.constraint, constraint))
                    .findFirst();
            }
        }
        return Optional.empty();
    }
}
