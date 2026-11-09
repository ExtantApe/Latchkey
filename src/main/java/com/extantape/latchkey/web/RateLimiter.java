package com.extantape.latchkey.web;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Two limits on tries: per visitor over 10 minutes, and for everyone together over a minute. A try counts whether or
 * not it works, so a guesser can't learn anything by watching which ones are counted.
 */
public final class RateLimiter {
    private static final long VISITOR_WINDOW = 10 * 60_000L;
    private static final long SITE_WINDOW = 60_000L;
    private static final int MAX_VISITORS_TRACKED = 50_000;

    private final int perVisitor;
    private final int siteWide;
    private final LongSupplier clock;
    private final Map<String, Deque<Long>> visitors = new HashMap<>();
    private final Deque<Long> site = new ArrayDeque<>();

    public RateLimiter(int perVisitorPer10Minutes, int siteWidePerMinute, LongSupplier clockMillis) {
        this.perVisitor = perVisitorPer10Minutes;
        this.siteWide = siteWidePerMinute;
        this.clock = clockMillis;
    }

    /** Zero if the try may go ahead (and counts it), otherwise how many seconds until it may. */
    public synchronized long tryAcquire(String visitor) {
        long now = clock.getAsLong();
        trim(site, now - SITE_WINDOW);
        Deque<Long> mine = visitors.computeIfAbsent(visitor, k -> new ArrayDeque<>());
        trim(mine, now - VISITOR_WINDOW);
        if (mine.size() >= perVisitor) return seconds(mine.peekFirst() + VISITOR_WINDOW - now);
        if (site.size() >= siteWide) return seconds(site.peekFirst() + SITE_WINDOW - now);
        mine.addLast(now);
        site.addLast(now);
        if (visitors.size() > MAX_VISITORS_TRACKED) forgetIdle(now);
        return 0;
    }

    private void forgetIdle(long now) {
        for (Iterator<Deque<Long>> it = visitors.values().iterator(); it.hasNext(); ) {
            Deque<Long> d = it.next();
            trim(d, now - VISITOR_WINDOW);
            if (d.isEmpty()) it.remove();
        }
    }

    private static void trim(Deque<Long> d, long before) {
        while (!d.isEmpty() && d.peekFirst() <= before) d.removeFirst();
    }

    private static long seconds(long millis) { return Math.max(1, (millis + 999) / 1000); }
}
