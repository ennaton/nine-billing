package co.nine.billing.metering;

public class UnknownMetricException extends RuntimeException {
    // Never crosses a serialization boundary; the value only exists to keep
    // -Xlint:serial quiet and must not change if the class does.
    private static final long serialVersionUID = 1L;

    public UnknownMetricException(String metric) {
        super("no price plan for metric '" + metric + "'");
    }
}
