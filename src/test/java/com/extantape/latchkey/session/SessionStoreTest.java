package com.extantape.latchkey.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {
    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    final UUID alex = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    final UUID sam = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    SessionStore store() throws Exception { return new SessionStore(dir.resolve("sessions"), now::get); }

    long files() throws Exception { try (Stream<Path> s = Files.list(dir.resolve("sessions"))) { return s.count(); } }

    @Test void signInGivesACookieThatLooksUpToThePlayer() throws Exception {
        var st = store();
        var c = st.signIn(alex, "Alex", SessionStore.DEFAULT_LIFETIME, null);
        assertEquals(43, c.cookie().length());
        var s = st.lookup(c.cookie()).orElseThrow();
        assertEquals(alex, s.uuid());
        assertEquals("Alex", s.name());
        assertEquals(1_800_000_000L + 86_400L, s.expires());
    }

    @Test void theCookieIsNeverWrittenToDisk() throws Exception {
        var st = store();
        var c = st.signIn(alex, "Alex", SessionStore.DEFAULT_LIFETIME, null);
        try (Stream<Path> s = Files.list(dir.resolve("sessions"))) {
            for (Path p : s.toList()) {
                assertFalse(p.getFileName().toString().contains(c.cookie()));
                assertFalse(Files.readString(p).contains(c.cookie()));
                assertTrue(p.getFileName().toString().matches("[0-9a-f]{64}\\.json"));
            }
        }
    }

    @Test void filesAreOwnerOnly() throws Exception {
        var st = store();
        st.signIn(alex, "Alex", SessionStore.DEFAULT_LIFETIME, null);
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("sessions"))));
        try (Stream<Path> s = Files.list(dir.resolve("sessions"))) {
            for (Path p : s.toList()) assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(p)));
        }
    }

    @Test void fileIsPlainJsonReadableWithoutThePlugin() throws Exception {
        var st = store();
        st.signIn(alex, "Alex", Duration.ofHours(2), null);
        try (Stream<Path> s = Files.list(dir.resolve("sessions"))) {
            String text = Files.readString(s.findFirst().orElseThrow());
            assertEquals("{\"uuid\":\"" + alex + "\",\"name\":\"Alex\",\"expires\":" + (1_800_000_000L + 7200) + ",\"since\":1800000000}\n", text);
        }
    }

    @Test void lifetimeOverThirtyDaysIsRefused() throws Exception {
        var st = store();
        assertThrows(IllegalArgumentException.class, () -> st.signIn(alex, "Alex", Duration.ofDays(30).plusSeconds(1), null));
        assertThrows(IllegalArgumentException.class, () -> st.signIn(alex, "Alex", Duration.ZERO, null));
        assertThrows(IllegalArgumentException.class, () -> st.signIn(alex, "Alex", Duration.ofSeconds(-5), null));
        assertDoesNotThrow(() -> st.signIn(alex, "Alex", Duration.ofDays(30), null));
    }

    @Test void oddNamesAreRefusedSoTheFileStaysParseable() throws Exception {
        var st = store();
        assertThrows(IllegalArgumentException.class, () -> st.signIn(alex, "Al\"ex", SessionStore.DEFAULT_LIFETIME, null));
        assertThrows(IllegalArgumentException.class, () -> st.signIn(alex, "", SessionStore.DEFAULT_LIFETIME, null));
        assertThrows(IllegalArgumentException.class, () -> st.signIn(alex, "x".repeat(17), SessionStore.DEFAULT_LIFETIME, null));
        assertEquals(0, files());
    }

    @Test void expiredSessionsSignNobodyInAndAreDeleted() throws Exception {
        var st = store();
        var c = st.signIn(alex, "Alex", Duration.ofHours(1), null);
        now.addAndGet(Duration.ofHours(1).toMillis() - 1000);
        assertTrue(st.lookup(c.cookie()).isPresent());
        now.addAndGet(1000);
        assertTrue(st.lookup(c.cookie()).isEmpty());
        assertEquals(0, files());
    }

    @Test void signingInAgainRotatesTheCookie() throws Exception {
        var st = store();
        var first = st.signIn(alex, "Alex", SessionStore.DEFAULT_LIFETIME, null);
        var second = st.signIn(alex, "Alex", SessionStore.DEFAULT_LIFETIME, first.cookie());
        assertNotEquals(first.cookie(), second.cookie());
        assertTrue(st.lookup(first.cookie()).isEmpty());
        assertTrue(st.lookup(second.cookie()).isPresent());
        assertEquals(1, files());
    }

    @Test void signOutEndsOnlyThatBrowser() throws Exception {
        var st = store();
        var a = st.signIn(alex, "Alex", SessionStore.DEFAULT_LIFETIME, null);
        var b = st.signIn(alex, "Alex", SessionStore.DEFAULT_LIFETIME, null);
        assertTrue(st.signOut(a.cookie()));
        assertFalse(st.signOut(a.cookie()));
        assertTrue(st.lookup(a.cookie()).isEmpty());
        assertTrue(st.lookup(b.cookie()).isPresent());
    }

    @Test void signOutEverywhereEndsAllOfOnePlayersSessionsAndNoOneElses() throws Exception {
        var st = store();
        var a1 = st.signIn(alex, "Alex", SessionStore.DEFAULT_LIFETIME, null);
        var a2 = st.signIn(alex, "Alex", SessionStore.DEFAULT_LIFETIME, null);
        var s1 = st.signIn(sam, "Sam", SessionStore.DEFAULT_LIFETIME, null);
        assertEquals(2, st.count(alex));
        assertEquals(2, st.signOutEverywhere(alex));
        assertTrue(st.lookup(a1.cookie()).isEmpty());
        assertTrue(st.lookup(a2.cookie()).isEmpty());
        assertTrue(st.lookup(s1.cookie()).isPresent());
        assertEquals(0, st.count(alex));
    }

    @Test void malformedFilesSignNobodyInAndAreCleanedUp() throws Exception {
        var st = store();
        var c = st.signIn(alex, "Alex", SessionStore.DEFAULT_LIFETIME, null);
        Path f;
        try (Stream<Path> s = Files.list(dir.resolve("sessions"))) { f = s.findFirst().orElseThrow(); }
        Files.writeString(f, "{\"uuid\":\"" + alex + "\",\"name\":\"Alex\",\"expires\":1800086400,\"since\":1800000000,\"admin\":true}");
        assertTrue(st.lookup(c.cookie()).isEmpty());
        assertFalse(Files.exists(f));

        st.signIn(sam, "Sam", SessionStore.DEFAULT_LIFETIME, null);
        Files.writeString(dir.resolve("sessions").resolve("0".repeat(64) + ".json"), "garbage");
        assertEquals(1, st.sweep());
        assertEquals(1, files());
    }

    @Test void aFileWithoutSinceSignsNobodyIn() throws Exception {
        var st = store();
        var c = st.signIn(alex, "Alex", SessionStore.DEFAULT_LIFETIME, null);
        try (Stream<Path> s = Files.list(dir.resolve("sessions"))) {
            Files.writeString(s.findFirst().orElseThrow(), "{\"uuid\":\"" + alex + "\",\"name\":\"Alex\",\"expires\":1800086400}\n");
        }
        assertTrue(st.lookup(c.cookie()).isEmpty());
    }

    @Test void badCookiesAreRejectedWithoutTouchingDisk() throws Exception {
        var st = store();
        assertTrue(st.lookup(null).isEmpty());
        assertTrue(st.lookup("").isEmpty());
        assertTrue(st.lookup("../../../etc/passwd").isEmpty());
        assertTrue(st.lookup("A".repeat(42)).isEmpty());
        assertTrue(st.lookup("A".repeat(43)).isEmpty());
        assertFalse(st.signOut("../x"));
    }

    @Test void sweepRemovesOnlyExpiredSessions() throws Exception {
        var st = store();
        st.signIn(alex, "Alex", Duration.ofHours(1), null);
        var keep = st.signIn(sam, "Sam", Duration.ofDays(2), null);
        now.addAndGet(Duration.ofHours(2).toMillis());
        assertEquals(1, st.sweep());
        assertTrue(st.lookup(keep.cookie()).isPresent());
    }

    @Test void sessionsSurviveARestart() throws Exception {
        var c = store().signIn(alex, "Alex", SessionStore.DEFAULT_LIFETIME, null);
        assertEquals(alex, store().lookup(c.cookie()).orElseThrow().uuid());
    }

    @Test void noTemporaryFilesAreLeftBehind() throws Exception {
        var st = store();
        for (int i = 0; i < 20; i++) st.signIn(alex, "Alex", SessionStore.DEFAULT_LIFETIME, null);
        try (Stream<Path> s = Files.list(dir.resolve("sessions"))) {
            assertTrue(s.allMatch(p -> p.getFileName().toString().endsWith(".json")));
        }
    }
}
