package com.extantape.latchkey.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The owner's rule of 2026-11-09: playing keeps a session open a day at a time, at most 7 days in a row. */
class ExtensionTest {
    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    final UUID alex = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    final Duration day = Duration.ofDays(1);

    SessionStore store() throws Exception { return new SessionStore(dir.resolve("sessions"), now::get); }

    void advanceTo(long epochSecond) { now.set(epochSecond * 1000); }

    @Test void anActivePlayerIsExtendedByADay() throws Exception {
        var st = store();
        var c = st.signIn(alex, "Alex", day, null);
        long first = c.session().expires();
        advanceTo(first - 300);
        assertEquals(1, st.extendActive(u -> true, day));
        assertEquals(first + 86_400, st.lookup(c.cookie()).orElseThrow().expires());
        advanceTo(first + 3600);
        assertTrue(st.lookup(c.cookie()).isPresent(), "still signed in after the first day");
    }

    @Test void anIdlePlayerIsNotExtended() throws Exception {
        var st = store();
        var c = st.signIn(alex, "Alex", day, null);
        long first = c.session().expires();
        advanceTo(first - 300);
        assertEquals(0, st.extendActive(u -> false, day));
        advanceTo(first);
        assertTrue(st.lookup(c.cookie()).isEmpty());
    }

    @Test void nothingIsExtendedBeforeItsLastTenMinutes() throws Exception {
        var st = store();
        var c = st.signIn(alex, "Alex", day, null);
        advanceTo(c.session().expires() - SessionStore.EXTEND_WINDOW.toSeconds() - 1);
        assertEquals(0, st.extendActive(u -> true, day));
    }

    @Test void theEighthDayInARowIsRefused() throws Exception {
        var st = store();
        var c = st.signIn(alex, "Alex", day, null);
        for (int i = 1; i <= 7; i++) {
            advanceTo(st.lookup(c.cookie()).orElseThrow().expires() - 60);
            assertEquals(1, st.extendActive(u -> true, day), "extension " + i);
        }
        long last = st.lookup(c.cookie()).orElseThrow().expires();
        assertEquals(c.session().since() + 8 * 86_400, last);
        advanceTo(last - 60);
        assertEquals(0, st.extendActive(u -> true, day), "the 8th is refused");
        advanceTo(last);
        assertTrue(st.lookup(c.cookie()).isEmpty(), "a fresh sign-in is needed");
    }

    @Test void signingInAgainStartsTheCountAfresh() throws Exception {
        var st = store();
        var c = st.signIn(alex, "Alex", day, null);
        for (int i = 0; i < 7; i++) {
            advanceTo(st.lookup(c.cookie()).orElseThrow().expires() - 60);
            st.extendActive(u -> true, day);
        }
        var again = st.signIn(alex, "Alex", day, c.cookie());
        advanceTo(again.session().expires() - 60);
        assertEquals(1, st.extendActive(u -> true, day));
    }

    @Test void theThirtyDayCeilingStillHolds() throws Exception {
        var st = store();
        var c = st.signIn(alex, "Alex", Duration.ofDays(30), null);
        advanceTo(c.session().expires() - 60);
        assertEquals(0, st.extendActive(u -> true, Duration.ofDays(30)));
        var d = st.signIn(alex, "Alex", Duration.ofDays(25), null);
        for (int i = 0; i < 7; i++) {
            advanceTo(st.lookup(d.cookie()).orElseThrow().expires() - 60);
            st.extendActive(u -> true, Duration.ofDays(25));
        }
        Session s = st.lookup(d.cookie()).orElseThrow();
        assertEquals(s.since() + 30 * 86_400, s.expires(), "five days of extensions, then the ceiling");
    }

    @Test void onlyThePlayingPlayersSessionIsExtended() throws Exception {
        var st = store();
        UUID sam = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        var a = st.signIn(alex, "Alex", day, null);
        var s = st.signIn(sam, "Sam", day, null);
        advanceTo(a.session().expires() - 60);
        assertEquals(1, st.extendActive(u -> u.equals(alex), day));
        advanceTo(a.session().expires() + 1);
        assertTrue(st.lookup(a.cookie()).isPresent());
        assertTrue(st.lookup(s.cookie()).isEmpty());
    }

    @Test void whitelistRemovalStillEndsAnExtendedSessionAtOnce() throws Exception {
        var st = store();
        var c = st.signIn(alex, "Alex", day, null);
        advanceTo(c.session().expires() - 60);
        st.extendActive(u -> true, day);
        assertEquals(1, st.signOutEverywhere(alex));
        assertTrue(st.lookup(c.cookie()).isEmpty());
        try (Stream<Path> f = Files.list(dir.resolve("sessions"))) { assertEquals(0, f.count()); }
    }

    @Test void theExtendedFileKeepsItsShapeAndPermissions() throws Exception {
        var st = store();
        var c = st.signIn(alex, "Alex", day, null);
        advanceTo(c.session().expires() - 60);
        st.extendActive(u -> true, day);
        try (Stream<Path> f = Files.list(dir.resolve("sessions"))) {
            Path p = f.findFirst().orElseThrow();
            assertEquals("{\"uuid\":\"" + alex + "\",\"name\":\"Alex\",\"expires\":" + (c.session().expires() + 86_400)
                    + ",\"since\":" + c.session().since() + "}\n", Files.readString(p));
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(p)));
        }
    }

    @Test void aFileClaimingMoreThanThirtyDaysIsRejected() throws Exception {
        var st = store();
        var c = st.signIn(alex, "Alex", day, null);
        try (Stream<Path> f = Files.list(dir.resolve("sessions"))) {
            Path p = f.findFirst().orElseThrow();
            long since = c.session().since();
            Files.writeString(p, "{\"uuid\":\"" + alex + "\",\"name\":\"Alex\",\"expires\":" + (since + 31 * 86_400L) + ",\"since\":" + since + "}\n");
        }
        assertTrue(st.lookup(c.cookie()).isEmpty());
    }

    @Test void activityMeansSomethingInTheLastFiveMinutes() {
        AtomicLong t = new AtomicLong(0);
        var a = new ActivityTracker(t::get);
        assertFalse(a.active(alex), "never seen");
        a.saw(alex);
        t.addAndGet(ActivityTracker.ACTIVE_FOR.toMillis());
        assertTrue(a.active(alex));
        t.addAndGet(1);
        assertFalse(a.active(alex), "standing idle for over five minutes");
        a.saw(alex);
        a.left(alex);
        assertFalse(a.active(alex), "forgotten on leaving");
    }

    @Test void turningOnTheSpotIsNotMoving() {
        assertFalse(ActivityTracker.changedBlock(1, 64, 1, 1, 64, 1));
        assertTrue(ActivityTracker.changedBlock(1, 64, 1, 2, 64, 1));
        assertTrue(ActivityTracker.changedBlock(1, 64, 1, 1, 65, 1));
    }
}
