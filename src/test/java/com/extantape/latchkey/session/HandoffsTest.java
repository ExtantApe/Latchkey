package com.extantape.latchkey.session;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class HandoffsTest {
    static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    final AtomicLong now = new AtomicLong(1_000_000);
    final Handoffs h = new Handoffs(now::get);
    final Session alex = new Session(ALEX, "Alex", 10_000, 0);
    final String ref = "a".repeat(64);

    @Test void aTokenWorksOnceForItsAudience() {
        String t = h.issue(alex, ref, "map");
        var g = h.redeem(t, "map").orElseThrow();
        assertEquals(ALEX, g.uuid());
        assertEquals("Alex", g.name());
        assertEquals(ref, g.sessionRef());
        assertTrue(h.redeem(t, "map").isEmpty());
    }

    @Test void theWrongAudienceGetsNothingAndUsesTheTokenUp() {
        String t = h.issue(alex, ref, "map");
        assertTrue(h.redeem(t, "wiki").isEmpty());
        assertTrue(h.redeem(t, "map").isEmpty(), "a token tried at the wrong place is gone");
    }

    @Test void aTokenRunsOutAfterAMinute() {
        String t = h.issue(alex, ref, "map");
        now.addAndGet(Handoffs.LIFETIME_MS + 1);
        assertTrue(h.redeem(t, "map").isEmpty());
        assertEquals(0, h.outstanding());
    }

    @Test void forgedAndOddTokensGetNothing() {
        h.issue(alex, ref, "map");
        for (String t : new String[]{null, "", "x", "A".repeat(43), "A".repeat(44), "../../" + "A".repeat(37), "A".repeat(42) + "="})
            assertTrue(h.redeem(t, "map").isEmpty(), String.valueOf(t));
    }

    @Test void tokensAreRandomAndUnguessablyLong() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String t = h.issue(new Session(UUID.randomUUID(), "P", 10_000, 0), Integer.toHexString(i), "map");
            assertTrue(t.matches("[A-Za-z0-9_-]{43}"));
            assertTrue(seen.add(t));
        }
    }

    @Test void oneSessionHoldsOnlyAFewAndTheTotalIsCapped() {
        String first = h.issue(alex, ref, "map");
        for (int i = 0; i < Handoffs.MAX_PER_SESSION; i++) h.issue(alex, ref, "map");
        assertEquals(Handoffs.MAX_PER_SESSION, h.outstanding());
        assertTrue(h.redeem(first, "map").isEmpty(), "the oldest went first");
        for (int i = 0; i < Handoffs.MAX_OUTSTANDING + 50; i++) h.issue(new Session(UUID.randomUUID(), "P", 10_000, 0), "r" + i, "map");
        assertEquals(Handoffs.MAX_OUTSTANDING, h.outstanding());
    }

    @Test void audiencesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> h.issue(alex, ref, "Map!"));
    }
}
