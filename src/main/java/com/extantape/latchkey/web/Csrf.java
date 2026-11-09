package com.extantape.latchkey.web;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Form tokens. The browser holds a random cookie; each form carries an HMAC of it under a key that lives only in memory.
 * A post is accepted only when the two agree, so another site can't submit the form for someone.
 */
public final class Csrf {
    public static final String COOKIE = "__Host-latchkey_form";
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] key = new byte[32];

    public Csrf() { RANDOM.nextBytes(key); }

    public static String newCookieValue() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static boolean wellFormed(String cookie) { return cookie != null && VALUE.matcher(cookie).matches(); }

    public String tokenFor(String cookie) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(cookie.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean valid(String cookie, String token) {
        if (!wellFormed(cookie) || token == null) return false;
        return MessageDigest.isEqual(tokenFor(cookie).getBytes(StandardCharsets.US_ASCII), token.getBytes(StandardCharsets.US_ASCII));
    }
}
