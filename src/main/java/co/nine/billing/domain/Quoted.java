package co.nine.billing.domain;

/**
 * One rule for quoting a refused value back: the caller sees what we read, and
 * the response does not follow the request's size. The bound is per field
 * because a currency code and a media type have different natural lengths.
 */
public final class Quoted {

    private Quoted() {}

    public static String value(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
