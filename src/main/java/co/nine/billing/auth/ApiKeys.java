package co.nine.billing.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Key generation and hashing. Plaintext keys exist in memory once, at creation, and in the caller's hands. */
public final class ApiKeys {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String PREFIX = "nk_";   // nine key

    private ApiKeys() {}

    public static String generate() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String hash(String plaintext) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String prefixOf(String plaintext) {
        return plaintext.length() >= 8 ? plaintext.substring(0, 8) : plaintext;
    }
}
