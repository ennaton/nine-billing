package co.nine.billing.metering;

public class UnknownMetricException extends RuntimeException {
    public UnknownMetricException(String metric) {
        super("no price plan for metric '" + metric + "'");
    }
}
