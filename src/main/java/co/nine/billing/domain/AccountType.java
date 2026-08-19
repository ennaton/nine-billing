package co.nine.billing.domain;

/**
 * Chart-of-accounts type. ASSET and EXPENSE carry a debit-normal balance, the
 * rest credit-normal. The database view uses the same rule; this enum exists
 * so the Java side can reason about sign without querying.
 */
public enum AccountType {
    ASSET, LIABILITY, REVENUE, EXPENSE;

    public boolean isDebitNormal() {
        return this == ASSET || this == EXPENSE;
    }
}
