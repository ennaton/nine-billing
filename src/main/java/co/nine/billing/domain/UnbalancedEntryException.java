package co.nine.billing.domain;

public class UnbalancedEntryException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public UnbalancedEntryException(long debits, long credits) {
        super("entry does not balance: debits=" + debits + " credits=" + credits
              + " imbalance=" + (debits - credits));
    }
}
