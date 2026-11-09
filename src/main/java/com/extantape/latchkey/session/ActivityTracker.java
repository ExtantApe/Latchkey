package com.extantape.latchkey.session;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Who is actually playing: moved to a different block or interacted with the world in the last five minutes
 * (owner, 2026-11-09). Turning on the spot isn't counted by the plugin's listeners. Memory only: nothing here is written
 * anywhere, and a player is forgotten when they leave.
 */
public final class ActivityTracker {
    public static final Duration ACTIVE_FOR = Duration.ofMinutes(5);

    private final LongSupplier clockMillis;
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();

    public ActivityTracker(LongSupplier clockMillis) { this.clockMillis = clockMillis; }

    /** Something the player did themselves. */
    public void saw(UUID player) { last.put(player, clockMillis.getAsLong()); }

    public void left(UUID player) { last.remove(player); }

    public boolean active(UUID player) {
        Long t = last.get(player);
        return t != null && clockMillis.getAsLong() - t <= ACTIVE_FOR.toMillis();
    }

    /** Whether a move counts: only a change of block, never just looking around. */
    public static boolean changedBlock(int fx, int fy, int fz, int tx, int ty, int tz) {
        return fx != tx || fy != ty || fz != tz;
    }
}
