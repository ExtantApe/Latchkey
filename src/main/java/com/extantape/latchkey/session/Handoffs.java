package com.extantape.latchkey.session;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * One-time tokens that carry a signed-in browser over to another of the server's own sites, such as the map (owner,
 * 2026-11-10, D43). A token is random, good once, for one audience, and for {@link #LIFETIME_MS} only. It names the
 * player and the session it came from, so the other site can ask later whether that session is still live.
 *
 * <p>Tokens live in memory only. A restart drops them, which just means clicking the link again.
 */
public final class Handoffs {
    public static final long LIFETIME_MS = 60_000;
    /** At most this many unused tokens at once; the oldest goes first. */
    static final int MAX_OUTSTANDING = 1_000;
    /** At most this many unused tokens per session, so one browser can't crowd others out. */
    static final int MAX_PER_SESSION = 3;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern AUDIENCE = Pattern.compile("[a-z]{1,16}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Who a redeemed token was for. {@code sessionRef} is opaque; pass it back to {@link SessionStore#liveByRef}. */
    public record Grant(UUID uuid, String name, String sessionRef, String audience) {}

    private record Pending(Grant grant, long until) {}

    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final LongSupplier clock;

    public Handoffs(LongSupplier clockMillis) { this.clock = clockMillis; }

    /** A new token for this session and audience. */
    public synchronized String issue(Session s, String sessionRef, String audience) {
        if (!AUDIENCE.matcher(audience).matches()) throw new IllegalArgumentException("Audiences are 1 to 16 of a-z.");
        long now = clock.getAsLong();
        forgetExpired(now);
        int mine = 0;
        String oldestMine = null;
        for (Map.Entry<String, Pending> e : pending.entrySet()) {
            if (e.getValue().grant().sessionRef().equals(sessionRef)) {
                if (oldestMine == null) oldestMine = e.getKey();
                mine++;
            }
        }
        if (mine >= MAX_PER_SESSION) pending.remove(oldestMine);
        if (pending.size() >= MAX_OUTSTANDING) {
            Iterator<String> it = pending.keySet().iterator();
            it.next();
            it.remove();
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        pending.put(token, new Pending(new Grant(s.uuid(), s.name(), sessionRef, audience), now + LIFETIME_MS));
        return token;
    }

    /** The grant for a token, once. Wrong audience, expired, unknown or malformed: empty, and the token is gone either way. */
    public synchronized Optional<Grant> redeem(String token, String audience) {
        if (token == null || !TOKEN.matcher(token).matches()) return Optional.empty();
        long now = clock.getAsLong();
        Pending p = pending.remove(token);
        forgetExpired(now);
        if (p == null || p.until() < now || !p.grant().audience().equals(audience)) return Optional.empty();
        return Optional.of(p.grant());
    }

    public synchronized int outstanding() { return pending.size(); }

    private void forgetExpired(long now) {
        pending.values().removeIf(p -> p.until() < now);
    }
}
