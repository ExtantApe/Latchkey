package com.extantape.latchkey;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Makes one-time links. Only a hash of each secret reaches the disk, in a file named after that hash, so
 * nothing in the folder can be turned back into a working link. Whatever serves the page deletes the file
 * when the link is used.
 */
final class SignInLinks {
    static final Duration LIFETIME = Duration.ofMinutes(2);
    static final Duration COOLDOWN = Duration.ofSeconds(10);

    private final Path folder;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Issued> latest = new HashMap<>();

    private record Issued(Path file, Instant at) {}

    SignInLinks(Path folder) {
        this.folder = folder;
    }

    /** Null while the player has to wait. A new link cancels their last one. */
    String issue(UUID player, String name, Instant now) throws IOException {
        Issued last = latest.get(player);
        if (last != null && now.isBefore(last.at().plus(COOLDOWN))) {
            return null;
        }
        Files.createDirectories(folder);
        removeExpired(now);
        if (last != null) {
            Files.deleteIfExists(last.file());
        }
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);

        JsonObject entry = new JsonObject();
        entry.addProperty("uuid", player.toString());
        entry.addProperty("name", name);
        entry.addProperty("expires", now.plus(LIFETIME).getEpochSecond());

        Path file = folder.resolve(hash(token) + ".json");
        // Written aside and moved in, so a reader never sees half of it.
        Path temporary = Files.createTempFile(folder, "new", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temporary, entry.toString(), StandardCharsets.UTF_8);
            moveInto(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
        latest.put(player, new Issued(file, now));
        return token;
    }

    private static void moveInto(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    /** Goes by when each file was written, which is all it needs to know. */
    void removeExpired(Instant now) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path file : files) {
                if (Files.getLastModifiedTime(file).toInstant().plus(LIFETIME).isBefore(now)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /** Removes every link waiting to be used. A stopped server shouldn't leave any open. */
    void removeAll() throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        }
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The secret goes after a #, which a browser never sends in a request, so it can't land in a web log. */
    static String link(String page, String token) {
        return page.replaceAll("/+$", "") + "/signin#" + token;
    }

    /** Only a plain https address with a host. A link anyone else could read would give the sign-in away. */
    static boolean usablePage(String page) {
        if (page == null || page.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(page.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
