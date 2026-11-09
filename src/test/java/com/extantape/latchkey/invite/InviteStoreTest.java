package com.extantape.latchkey.invite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InviteStoreTest {
    static final UUID BO = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    final AtomicLong now = new AtomicLong(1_000_000L);

    @Test
    void codesUseTheNoLookAlikeAlphabet() {
        for (int i = 0; i < 500; i++) {
            String c = InviteCodes.generate();
            assertEquals(6, c.length());
            assertTrue(c.chars().allMatch(ch -> InviteCodes.ALPHABET.indexOf(ch) >= 0), c);
        }
        assertEquals(25, InviteCodes.ALPHABET.length());
        for (char bad : "0O1IL2Z5S8B".toCharArray()) assertEquals(-1, InviteCodes.ALPHABET.indexOf(bad), "no " + bad);
        assertEquals("K7QX4M", InviteCodes.normalize(" k7q-x4m "));
        assertNull(InviteCodes.normalize("K7QX40"), "0 isn't in the alphabet");
        assertNull(InviteCodes.normalize("K7QX4"));
    }

    @Test
    void onlyTheHashIsStoredAndFilesAreOwnerOnly(@TempDir Path dir) throws Exception {
        var store = new InviteStore(dir, now::get);
        var c = store.create(BO, "Bo", List.of("kid"), Duration.ofDays(14));
        String file = Files.readString(dir.resolve("invites.tsv"));
        assertFalse(file.contains(c.code()), "the code itself is never written");
        assertTrue(file.contains(c.invite().codeHash()));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("invites.tsv"))));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("invite-salt"))));
    }

    @Test
    void redeemsOnceForTheRightNameInAnyCase(@TempDir Path dir) throws Exception {
        var store = new InviteStore(dir, now::get);
        var c = store.create(BO, "Bo", List.of("kid", "builder"), Duration.ofDays(14));
        assertTrue(store.redeem(c.code(), "Someone_else").isEmpty(), "tied to one username");
        var ok = store.redeem(c.code().toLowerCase(), "bo");
        assertTrue(ok.isPresent());
        assertEquals(List.of("kid", "builder"), ok.get().profiles());
        assertTrue(store.redeem(c.code(), "Bo").isEmpty(), "single use");
    }

    @Test
    void expiredAndRevokedCodesFailLikeWrongOnes(@TempDir Path dir) throws Exception {
        var store = new InviteStore(dir, now::get);
        var expiring = store.create(BO, "Bo", List.of(), Duration.ofDays(14));
        var revoked = store.create(BO, "Bo", List.of(), Duration.ofDays(14));
        assertTrue(store.revoke(revoked.invite().id()));
        now.addAndGet(Duration.ofDays(14).toMillis());
        assertTrue(store.redeem(expiring.code(), "Bo").isEmpty(), "expired after 14 days");
        assertTrue(store.redeem(revoked.code(), "Bo").isEmpty());
        assertTrue(store.redeem("AAAAAA", "Bo").isEmpty());
        assertTrue(store.redeem("not a code", "Bo").isEmpty());
    }

    @Test
    void neverMoreThan30Days(@TempDir Path dir) throws Exception {
        var store = new InviteStore(dir, now::get);
        assertThrows(IllegalArgumentException.class, () -> store.create(BO, "Bo", List.of(), Duration.ofDays(31)));
    }

    @Test
    void survivesARestart(@TempDir Path dir) throws Exception {
        var c = new InviteStore(dir, now::get).create(BO, "Bo", List.of("kid"), Duration.ofDays(14));
        var again = new InviteStore(dir, now::get);
        assertTrue(again.redeem(c.code(), "Bo").isPresent(), "same salt after a restart");
    }

    @Test
    void twoRedemptionsRacingOnlyOneWins(@TempDir Path dir) throws Exception {
        var store = new InviteStore(dir, now::get);
        var c = store.create(BO, "Bo", List.of(), Duration.ofDays(14));
        AtomicInteger wins = new AtomicInteger();
        Thread[] ts = new Thread[16];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> { try { if (store.redeem(c.code(), "Bo").isPresent()) wins.incrementAndGet(); } catch (Exception e) { throw new RuntimeException(e); } });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        assertEquals(1, wins.get());
    }
}
