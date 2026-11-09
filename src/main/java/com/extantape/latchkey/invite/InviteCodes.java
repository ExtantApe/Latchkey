package com.extantape.latchkey.invite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Invite codes: 6 characters from 25 that can't be mistaken for each other (no 0/O, 1/I/L, 2/Z, 5/S, 8/B), typed in
 * any case. That's 25^6, about 244 million codes, and the landing page's rate limits make guessing hopeless. Only a
 * salted hash of each code is ever stored; the code itself is shown once.
 */
public final class InviteCodes {
    public static final String ALPHABET = "34679ACDEFGHJKMNPQRTUVWXY";
    public static final int LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteCodes() {}

    public static String generate() {
        StringBuilder b = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return b.toString();
    }

    /** What a person typed, made comparable: upper case, spaces and dashes dropped. Null if it can't be a code. */
    public static String normalize(String typed) {
        if (typed == null) return null;
        String s = typed.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
        if (s.length() != LENGTH) return null;
        for (int i = 0; i < s.length(); i++) if (ALPHABET.indexOf(s.charAt(i)) < 0) return null;
        return s;
    }

    /** The stored form: SHA-256 of the server's salt and the normalized code, as hex. */
    public static String hash(byte[] salt, String normalized) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(normalized.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
