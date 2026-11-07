package com.extantape.latchkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignInLinksTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @TempDir
    Path folder;

    private List<Path> files() throws IOException {
        try (Stream<Path> found = Files.list(folder)) {
            return found.toList();
        }
    }

    @Test
    void aLinkIsSavedOnlyAsItsHash() throws IOException {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        String token = new SignInLinks(folder).issue(PLAYER, "MadeUp", now);
        assertNotNull(token);
        assertEquals(43, token.length());
        assertEquals(1, files().size());
        Path file = files().get(0);
        assertEquals(SignInLinks.hash(token) + ".json", file.getFileName().toString());
        String saved = Files.readString(file);
        assertFalse(saved.contains(token));
        JsonObject entry = JsonParser.parseString(saved).getAsJsonObject();
        assertEquals(PLAYER.toString(), entry.get("uuid").getAsString());
        assertEquals("MadeUp", entry.get("name").getAsString());
        assertEquals(now.getEpochSecond() + 120, entry.get("expires").getAsLong());
    }

    @Test
    void onlyTheOwnerCanReadIt() throws IOException {
        new SignInLinks(folder).issue(PLAYER, "MadeUp", Instant.now());
        var permissions = Files.getPosixFilePermissions(files().get(0));
        assertTrue(permissions.contains(PosixFilePermission.OWNER_READ));
        assertFalse(permissions.contains(PosixFilePermission.GROUP_READ));
        assertFalse(permissions.contains(PosixFilePermission.OTHERS_READ));
    }

    @Test
    void askingAgainWaitsThenCancelsTheOldLink() throws IOException {
        SignInLinks links = new SignInLinks(folder);
        Instant now = Instant.now();
        String first = links.issue(PLAYER, "MadeUp", now);
        assertNull(links.issue(PLAYER, "MadeUp", now.plusSeconds(5)));
        String second = links.issue(PLAYER, "MadeUp", now.plusSeconds(11));
        assertNotEquals(first, second);
        assertEquals(1, files().size());
        assertEquals(SignInLinks.hash(second) + ".json", files().get(0).getFileName().toString());
    }

    @Test
    void oldLinksAreCleared() throws IOException {
        SignInLinks links = new SignInLinks(folder);
        Instant now = Instant.now();
        links.issue(PLAYER, "MadeUp", now);
        Files.setLastModifiedTime(files().get(0), FileTime.from(now.minusSeconds(300)));
        links.removeExpired(now);
        assertEquals(0, files().size());
        links.issue(UUID.randomUUID(), "Other", now);
        links.removeAll();
        assertEquals(0, files().size());
    }

    @Test
    void theSecretStaysAfterTheHash() {
        assertEquals("https://example.test/signin#abc", SignInLinks.link("https://example.test/", "abc"));
    }

    @Test
    void onlyAPlainHttpsPageIsUsable() {
        assertTrue(SignInLinks.usablePage("https://links.example.test"));
        assertTrue(SignInLinks.usablePage("https://links.example.test/path"));
        for (String bad : new String[] {"", " ", null, "http://links.example.test", "javascript:alert(1)", "https://", "https://user@example.com", "https://example.test?a=1", "https://example.test#a", "not a page"}) {
            assertFalse(SignInLinks.usablePage(bad), String.valueOf(bad));
        }
    }
}
